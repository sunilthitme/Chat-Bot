import { CommonModule } from '@angular/common';
import { HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { AfterViewChecked, Component, ElementRef, OnDestroy, OnInit, ViewChild, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject, Subscription, debounceTime, distinctUntilChanged, finalize, switchMap, timer } from 'rxjs';
import { ChatService, ChatSession, IngestionStatusResponse, SourceReference } from './chat.service';
import { ChatSessionManagerService } from './chat-session-manager.service';

type Sender = 'user' | 'bot';

interface ChatMessage {
  sender: Sender;
  text: string;
  sources?: SourceReference[];
  streaming?: boolean;
}

@Component({
  selector: 'app-root',
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatTooltipModule
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class AppComponent implements AfterViewChecked, OnDestroy, OnInit {
  userInput = '';
  urlInput = '';
  sessionSearch = '';
  renameTitle = '';

  readonly debouncedInput = signal('');
  readonly isLoading = signal(false);
  readonly isSidebarLoading = signal(false);
  readonly uploadProgress = signal(0);
  readonly indexingActive = signal(false);
  readonly indexingStage = signal('');
  readonly indexingMessage = signal('');
  readonly privateMode = signal(false);
  readonly sessions = signal<ChatSession[]>([]);
  readonly localSessions = signal<ChatSession[]>([]);
  readonly currentSessionId = signal('');
  readonly renamingSessionId = signal('');

  readonly messages = signal<ChatMessage[]>([
    {
      sender: 'bot',
      text: 'Welcome to TD Internal Chat Bot. Start a new chat, upload a document, or ask a question.'
    }
  ]);

  @ViewChild('messagesContainer')
  private messagesContainer?: ElementRef<HTMLElement>;

  private shouldScrollMessages = true;
  private readonly inputChanges = new Subject<string>();
  private readonly localSessionMessages = new Map<string, ChatMessage[]>();
  private inputSubscription?: Subscription;
  private activeChatSubscription?: Subscription;
  private indexingStatusSubscription?: Subscription;
  private sessionsRequestInFlight = false;
  private initialSessionCreationInFlight = false;
  private lastSubmitAt = 0;
  private lastSubmittedMessage = '';

  constructor(
    private readonly chatService: ChatService,
    private readonly sessionManager: ChatSessionManagerService
  ) {}

  ngOnInit(): void {
    this.inputSubscription = this.inputChanges
      .pipe(debounceTime(500), distinctUntilChanged())
      .subscribe((value) => this.debouncedInput.set(value.trim()));
    this.loadSessions();
  }

  ngOnDestroy(): void {
    this.inputSubscription?.unsubscribe();
    this.activeChatSubscription?.unsubscribe();
    this.indexingStatusSubscription?.unsubscribe();
  }

  ngAfterViewChecked(): void {
    if (!this.shouldScrollMessages) {
      return;
    }

    this.shouldScrollMessages = false;
    const container = this.messagesContainer?.nativeElement;
    if (container) {
      container.scrollTop = container.scrollHeight;
    }
  }

  sendMessage(): void {
    const message = this.userInput.trim();
    const now = Date.now();

    if (!message || this.isLoading() || this.indexingActive() || this.activeChatSubscription) {
      return;
    }

    if (message === this.lastSubmittedMessage && now - this.lastSubmitAt < 1000) {
      return;
    }

    this.lastSubmitAt = now;
    this.lastSubmittedMessage = message;
    this.appendMessage({ sender: 'user', text: message });
    const botIndex = this.appendMessage({ sender: 'bot', text: '', streaming: true });
    this.userInput = '';
    this.debouncedInput.set('');
    this.inputChanges.next('');
    this.isLoading.set(true);

    this.ensureSessionThen((sessionId) => {
      this.activeChatSubscription = this.chatService.streamAsk({
        message,
        sessionId,
        userKey: 'local-user',
        privateMode: this.privateMode()
      })
        .pipe(finalize(() => {
          this.isLoading.set(false);
          this.activeChatSubscription = undefined;
        }))
        .subscribe({
          next: (event) => {
            if (event.type === 'token') {
              this.updateMessage(botIndex, (messageToUpdate) => ({
                ...messageToUpdate,
                text: messageToUpdate.text + event.token
              }));
            }
            if (event.type === 'done') {
              this.privateMode.set(event.response.privateMode);
              this.updateMessage(botIndex, () => ({
                sender: 'bot',
                text: event.response.reply,
                sources: event.response.sources ?? [],
                streaming: false
              }));
              if (!this.isLocalSession(sessionId)) {
                this.loadSessions();
              }
            }
          },
          error: () => {
            this.updateMessage(botIndex, () => ({
              sender: 'bot',
              text: 'Backend is not reachable. Please make sure Spring Boot is running.',
              streaming: false
            }));
          }
        });
    });
  }

  newChat(): void {
    if (this.isSidebarLoading() || this.isLoading() || this.indexingActive()) {
      return;
    }

    this.rememberCurrentLocalMessages();
    this.clearIndexingState();
    const title = this.sessionManager.nextChatTitle(this.allSessions());

    if (this.privateMode()) {
      this.openLocalPrivateSession(title);
      return;
    }

    this.isSidebarLoading.set(true);
    this.chatService.createSession(false, title)
      .pipe(finalize(() => {
        this.isSidebarLoading.set(false);
        this.initialSessionCreationInFlight = false;
      }))
      .subscribe({
        next: (session) => {
          this.openFreshSession(session, 'New chat started. Ask a question, upload a document, or index a URL.');
          this.loadSessions();
        }
      });
  }

  selectSession(session: ChatSession): void {
    if (this.isLoading() || this.indexingActive() || session.id === this.currentSessionId()) {
      return;
    }

    this.rememberCurrentLocalMessages();
    this.currentSessionId.set(session.id);
    this.privateMode.set(session.privateMode);
    this.clearIndexingState();

    if (this.isLocalSession(session.id)) {
      this.messages.set(this.localSessionMessages.get(session.id) ?? [{
        sender: 'bot',
        text: 'Private session is active. Messages, uploads, embeddings, and renames stay in this browser session only.'
      }]);
      this.shouldScrollMessages = true;
      return;
    }

    this.chatService.listMessages(session.id).subscribe({
      next: (messages) => {
        const mappedMessages = messages.map<ChatMessage>((message) => ({
          sender: message.role === 'user' ? 'user' : 'bot',
          text: message.content
        }));
        this.messages.set(mappedMessages.length > 0 ? mappedMessages : [{
          sender: 'bot',
          text: 'This chat has no saved messages yet.'
        }]);
        this.shouldScrollMessages = true;
        this.refreshIndexingStatus(session.id);
      }
    });
  }

  togglePrivateMode(): void {
    if (this.indexingActive()) {
      return;
    }

    const nextValue = !this.privateMode();
    const sessionId = this.currentSessionId();
    this.privateMode.set(nextValue);

    if (!sessionId) {
      return;
    }

    if (this.isLocalSession(sessionId)) {
      this.updateLocalSession(sessionId, { privateMode: nextValue });
      if (!nextValue) {
        this.persistLocalSession(sessionId);
      }
      return;
    }

    this.sessions.update((sessions) => sessions.map((session) => (
      session.id === sessionId ? { ...session, privateMode: nextValue } : session
    )));
  }

  uploadDocument(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || this.isLoading() || this.indexingActive()) {
      return;
    }

    this.isLoading.set(true);
    this.uploadProgress.set(0);
    this.ensureSessionThen((sessionId) => {
      this.chatService.uploadDocument(file, sessionId, this.privateMode())
        .pipe(finalize(() => {
          this.isLoading.set(false);
          this.uploadProgress.set(0);
        }))
        .subscribe({
          next: (event) => {
            if (event.type === HttpEventType.UploadProgress && event.total) {
              this.uploadProgress.set(Math.round((100 * event.loaded) / event.total));
            }
            if (event.type === HttpEventType.Response && event.body) {
              const response = event.body;
              this.appendMessage({ sender: 'bot', text: response.message });
              this.startIndexingWatch(sessionId, response.status);
              if (!this.isLocalSession(sessionId)) {
                this.loadSessions();
              }
            }
          },
          error: (error) => this.appendMessage({ sender: 'bot', text: this.errorMessage(error, 'Document upload failed.') })
        });
    });
  }

  ingestUrl(): void {
    const url = this.urlInput.trim();
    if (!url || this.isLoading() || this.indexingActive()) {
      return;
    }

    this.urlInput = '';
    this.isLoading.set(true);
    this.ensureSessionThen((sessionId) => {
      this.chatService.ingestUrl(url, sessionId, this.privateMode())
        .pipe(finalize(() => this.isLoading.set(false)))
        .subscribe({
          next: (response) => {
            this.appendMessage({ sender: 'bot', text: response.message });
            this.startIndexingWatch(sessionId, response.status);
            if (!this.isLocalSession(sessionId)) {
              this.loadSessions();
            }
          },
          error: (error) => this.appendMessage({ sender: 'bot', text: this.errorMessage(error, 'URL could not be read.') })
        });
    });
  }

  onUserInputChange(value: string): void {
    this.inputChanges.next(value);
  }

  startRename(session: ChatSession, event?: Event): void {
    event?.stopPropagation();
    if (this.indexingActive()) {
      return;
    }
    this.renamingSessionId.set(session.id);
    this.renameTitle = session.title;
  }

  cancelRename(): void {
    this.renamingSessionId.set('');
    this.renameTitle = '';
  }

  confirmRename(session: ChatSession): void {
    const title = this.renameTitle.trim();
    this.cancelRename();
    if (!title || title === session.title) {
      return;
    }

    this.replaceSessionTitle(session.id, title);
    if (session.privateMode || this.isLocalSession(session.id)) {
      return;
    }

    this.chatService.renameSession(session.id, title, false).subscribe({
      next: (updatedSession) => this.replacePersistedSession(updatedSession),
      error: () => this.replaceSessionTitle(session.id, session.title)
    });
  }

  hideSessionFromUi(session: ChatSession, event: Event): void {
    event.stopPropagation();
    if (this.indexingActive()) {
      return;
    }

    this.sessionManager.hideSession(session.id);
    this.localSessions.update((sessions) => sessions.filter((item) => item.id !== session.id));
    this.sessions.update((sessions) => sessions.filter((item) => item.id !== session.id));
    this.localSessionMessages.delete(session.id);

    if (session.id === this.currentSessionId()) {
      const nextSession = this.filteredSessions()[0];
      if (nextSession) {
        this.currentSessionId.set('');
        this.selectSession(nextSession);
      } else {
        this.currentSessionId.set('');
        this.messages.set([{
          sender: 'bot',
          text: 'Chat hidden from this UI session. Start a new chat or adjust the search filter to continue.'
        }]);
      }
    }
  }

  filteredSessions(): ChatSession[] {
    const query = this.sessionSearch.trim().toLowerCase();
    return this.sessionManager
      .visibleSessions(this.allSessions())
      .filter((session) => {
        if (!query) {
          return true;
        }
        return [
          session.title,
          session.activeDocumentName ?? '',
          session.privateMode ? 'private' : 'saved'
        ].some((value) => value.toLowerCase().includes(query));
      });
  }

  currentSessionTitle(): string {
    return this.allSessions().find((session) => session.id === this.currentSessionId())?.title ?? 'No active chat';
  }

  currentSessionIndicator(): string {
    const session = this.allSessions().find((item) => item.id === this.currentSessionId());
    if (!session) {
      return 'No active session';
    }
    return session.privateMode ? 'Private session' : `Session ${session.id.slice(0, 8)}`;
  }

  activeDocumentLabel(): string {
    const session = this.allSessions().find((item) => item.id === this.currentSessionId());
    return session?.activeDocumentName ? `Active document: ${session.activeDocumentName}` : 'Memory-aware enterprise AI assistant';
  }

  sessionSubtitle(session: ChatSession): string {
    if (session.privateMode) {
      return 'Private - session only';
    }
    return session.activeDocumentName ? session.activeDocumentName : 'Saved enterprise chat';
  }

  isRenaming(session: ChatSession): boolean {
    return this.renamingSessionId() === session.id;
  }

  trackBySessionId(_: number, session: ChatSession): string {
    return session.id;
  }

  renderMarkdown(text: string): string {
    const escaped = this.escapeHtml(text || '');
    return escaped
      .replace(/```([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/^### (.*)$/gm, '<h3>$1</h3>')
      .replace(/^## (.*)$/gm, '<h2>$1</h2>')
      .replace(/^# (.*)$/gm, '<h1>$1</h1>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/\n/g, '<br>');
  }

  private loadSessions(): void {
    if (this.sessionsRequestInFlight) {
      return;
    }

    this.sessionsRequestInFlight = true;
    this.chatService.listSessions()
      .pipe(finalize(() => this.sessionsRequestInFlight = false))
      .subscribe({
        next: (sessions) => {
          const visibleSessions = this.sessionManager.visibleSessions(sessions);
          this.sessions.set(visibleSessions);
          const activeExists = this.allSessions().some((session) => session.id === this.currentSessionId());

          if (this.currentSessionId() && !activeExists) {
            this.currentSessionId.set('');
          }
          if (!this.currentSessionId() && this.filteredSessions().length > 0) {
            this.selectSession(this.filteredSessions()[0]);
          }
          if (!this.currentSessionId() && this.filteredSessions().length === 0 && !this.initialSessionCreationInFlight) {
            this.initialSessionCreationInFlight = true;
            this.newChat();
          }
        }
      });
  }

  private ensureSessionThen(callback: (sessionId: string) => void): void {
    const sessionId = this.currentSessionId();
    if (sessionId) {
      callback(sessionId);
      return;
    }

    if (this.privateMode()) {
      const session = this.openLocalPrivateSession(this.sessionManager.nextChatTitle(this.allSessions()));
      callback(session.id);
      return;
    }

    const title = this.sessionManager.nextChatTitle(this.allSessions());
    this.chatService.createSession(false, title).subscribe({
      next: (session) => {
        this.openFreshSession(session, 'New chat started.');
        callback(session.id);
      },
      error: () => {
        this.isLoading.set(false);
        this.appendMessage({ sender: 'bot', text: 'Unable to create chat session.' });
      }
    });
  }

  private refreshIndexingStatus(sessionId: string): void {
    if (this.isLocalSession(sessionId)) {
      return;
    }
    this.chatService.ingestionStatus(sessionId).subscribe({
      next: (status) => {
        this.applyIndexingStatus(status);
        if (status.active) {
          this.startIndexingWatch(sessionId, status.status);
        }
      }
    });
  }

  private startIndexingWatch(sessionId: string, status: string): void {
    if (!this.isActiveIndexingStatus(status) || this.privateMode()) {
      return;
    }

    this.indexingStatusSubscription?.unsubscribe();
    this.indexingActive.set(true);
    this.indexingStage.set('Indexing started');
    this.indexingMessage.set('Preparing content for enterprise search and summary.');

    this.indexingStatusSubscription = timer(0, 1500)
      .pipe(switchMap(() => this.chatService.ingestionStatus(sessionId)))
      .subscribe({
        next: (latestStatus) => {
          this.applyIndexingStatus(latestStatus);
          if (!latestStatus.active) {
            this.indexingStatusSubscription?.unsubscribe();
            this.indexingStatusSubscription = undefined;
            const completionMessage = this.indexingCompletionMessage(latestStatus);
            if (completionMessage) {
              this.appendMessage({ sender: 'bot', text: completionMessage });
            }
            this.loadSessions();
          }
        },
        error: () => {
          this.indexingActive.set(false);
          this.indexingStage.set('');
          this.indexingMessage.set('');
          this.indexingStatusSubscription = undefined;
          this.appendMessage({
            sender: 'bot',
            text: 'Indexing status is temporarily unavailable. Please try again shortly.'
          });
        }
      });
  }

  private applyIndexingStatus(status: IngestionStatusResponse): void {
    this.indexingActive.set(status.active);
    this.indexingStage.set(status.active ? status.stage : '');
    this.indexingMessage.set(status.active ? status.message : '');
  }

  private clearIndexingState(): void {
    this.indexingStatusSubscription?.unsubscribe();
    this.indexingStatusSubscription = undefined;
    this.indexingActive.set(false);
    this.indexingStage.set('');
    this.indexingMessage.set('');
  }

  private isActiveIndexingStatus(status: string): boolean {
    return ['QUEUED', 'EXTRACTING', 'INDEXING', 'CHUNKING', 'EMBEDDING', 'STORING', 'SUMMARIZING'].includes(status);
  }

  private indexingCompletionMessage(status: IngestionStatusResponse): string {
    const summary = status.summary?.trim();
    if (status.status === 'COMPLETED') {
      return [
        'Content indexed successfully.',
        summary ? `Summary:\n${summary}` : '',
        'You can now ask questions about this content.'
      ].filter(Boolean).join('\n\n');
    }

    if (status.status === 'NO_EMBEDDINGS') {
      return [
        'Content was extracted, but no searchable embeddings were stored.',
        summary ? `Summary:\n${summary}` : '',
        'Please try a clearer document or webpage if answers are incomplete.'
      ].filter(Boolean).join('\n\n');
    }

    if (status.status === 'FAILED') {
      return status.message || 'Indexing failed. Please try another document or URL.';
    }

    return '';
  }

  private openFreshSession(session: ChatSession, greeting: string): void {
    this.currentSessionId.set(session.id);
    this.privateMode.set(session.privateMode);
    this.messages.set([{ sender: 'bot', text: greeting }]);
    this.shouldScrollMessages = true;
  }

  private openLocalPrivateSession(title: string): ChatSession {
    const session = this.sessionManager.createPrivateSession(title);
    this.localSessions.update((sessions) => [session, ...sessions]);
    this.currentSessionId.set(session.id);
    this.privateMode.set(true);
    const initialMessages = [{
      sender: 'bot' as const,
      text: 'Private chat started. Messages, uploads, embeddings, and renames stay in this browser session only.'
    }];
    this.messages.set(initialMessages);
    this.localSessionMessages.set(session.id, initialMessages);
    this.shouldScrollMessages = true;
    return session;
  }

  private persistLocalSession(sessionId: string): void {
    const localSession = this.localSessions().find((session) => session.id === sessionId);
    if (!localSession) {
      return;
    }
    this.isSidebarLoading.set(true);
    this.chatService.createSession(false, localSession.title)
      .pipe(finalize(() => this.isSidebarLoading.set(false)))
      .subscribe({
        next: (savedSession) => {
          this.localSessions.update((sessions) => sessions.filter((session) => session.id !== sessionId));
          this.localSessionMessages.delete(sessionId);
          this.currentSessionId.set(savedSession.id);
          this.privateMode.set(false);
          this.loadSessions();
        },
        error: () => this.privateMode.set(true)
      });
  }

  private rememberCurrentLocalMessages(): void {
    const sessionId = this.currentSessionId();
    if (sessionId && this.isLocalSession(sessionId)) {
      this.localSessionMessages.set(sessionId, this.messages());
    }
  }

  private appendMessage(message: ChatMessage): number {
    const index = this.messages().length;
    this.messages.update((messages) => [...messages, message]);
    this.rememberCurrentLocalMessages();
    this.shouldScrollMessages = true;
    return index;
  }

  private updateMessage(index: number, updater: (message: ChatMessage) => ChatMessage): void {
    this.messages.update((messages) => messages.map((message, messageIndex) => (
      messageIndex === index ? updater(message) : message
    )));
    this.rememberCurrentLocalMessages();
    this.shouldScrollMessages = true;
  }

  private replaceSessionTitle(sessionId: string, title: string): void {
    this.sessions.update((sessions) => sessions.map((session) => (
      session.id === sessionId ? { ...session, title } : session
    )));
    this.updateLocalSession(sessionId, { title });
  }

  private replacePersistedSession(updatedSession: ChatSession): void {
    this.sessions.update((sessions) => sessions.map((session) => (
      session.id === updatedSession.id ? updatedSession : session
    )));
  }

  private updateLocalSession(sessionId: string, patch: Partial<ChatSession>): void {
    this.localSessions.update((sessions) => sessions.map((session) => (
      session.id === sessionId ? { ...session, ...patch, updatedAt: new Date().toISOString() } : session
    )));
  }

  private allSessions(): ChatSession[] {
    return [...this.localSessions(), ...this.sessions()];
  }

  private isLocalSession(sessionId: string): boolean {
    return this.sessionManager.isPrivateSessionId(sessionId);
  }

  private escapeHtml(text: string): string {
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse && error.error?.error) {
      return `${fallback} ${error.error.error}`;
    }
    return fallback;
  }
}
