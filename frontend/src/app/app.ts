import { CommonModule } from '@angular/common';
import { HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { AfterViewChecked, Component, ElementRef, OnDestroy, OnInit, ViewChild, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subject, Subscription, debounceTime, distinctUntilChanged, finalize } from 'rxjs';
import { ChatService, ChatSession } from './chat.service';

type Sender = 'user' | 'bot';

interface ChatMessage {
  sender: Sender;
  text: string;
  streaming?: boolean;
}

// Root component renders the chatbot window and handles user input.
@Component({
  selector: 'app-root',
  imports: [CommonModule, FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class AppComponent implements AfterViewChecked, OnDestroy, OnInit {
  userInput = '';
  urlInput = '';
  readonly debouncedInput = signal('');
  readonly isLoading = signal(false);
  readonly isSidebarLoading = signal(false);
  readonly uploadProgress = signal(0);
  readonly privateMode = signal(false);
  readonly darkMode = signal(false);
  readonly sessions = signal<ChatSession[]>([]);
  readonly currentSessionId = signal('');

  readonly messages = signal<ChatMessage[]>([
    {
      sender: 'bot',
      text: 'Hi, ask me about your internal knowledge, uploaded documents, or trusted websites.'
    }
  ]);

  @ViewChild('messagesContainer')
  private messagesContainer?: ElementRef<HTMLElement>;

  private shouldScrollMessages = true;
  private readonly inputChanges = new Subject<string>();
  private inputSubscription?: Subscription;
  private activeChatSubscription?: Subscription;
  private sessionsRequestInFlight = false;
  private initialSessionCreationInFlight = false;
  private lastSubmitAt = 0;
  private lastSubmittedMessage = '';

  constructor(private readonly chatService: ChatService) {}

  ngOnInit(): void {
    this.inputSubscription = this.inputChanges
      .pipe(debounceTime(500), distinctUntilChanged())
      .subscribe((value) => this.debouncedInput.set(value.trim()));
    this.loadSessions();
  }

  ngOnDestroy(): void {
    this.inputSubscription?.unsubscribe();
    this.activeChatSubscription?.unsubscribe();
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

    if (!message || this.isLoading() || this.activeChatSubscription) {
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

    this.activeChatSubscription = this.chatService.streamAsk({
      message,
      sessionId: this.currentSessionId(),
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
              streaming: false
            }));
            this.loadSessions();
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
  }

  newChat(): void {
    if (this.isSidebarLoading() || this.isLoading()) {
      return;
    }

    this.isSidebarLoading.set(true);
    this.chatService.createSession(this.privateMode())
      .pipe(finalize(() => {
        this.isSidebarLoading.set(false);
        this.initialSessionCreationInFlight = false;
      }))
      .subscribe({
        next: (session) => {
          this.currentSessionId.set(session.id);
          this.privateMode.set(session.privateMode);
          this.messages.set([{
            sender: 'bot',
            text: 'New chat started. Ask me about internal knowledge, uploaded files, or trusted websites.'
          }]);
          this.loadSessions();
        }
      });
  }

  selectSession(session: ChatSession): void {
    if (this.isLoading()) {
      return;
    }

    this.currentSessionId.set(session.id);
    this.privateMode.set(session.privateMode);
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
      }
    });
  }

  togglePrivateMode(): void {
    const nextValue = !this.privateMode();
    this.privateMode.set(nextValue);
    const sessionId = this.currentSessionId();
    if (sessionId) {
      this.chatService.setPrivateMode(sessionId, nextValue).subscribe({
        next: (session) => {
          this.privateMode.set(session.privateMode);
          this.loadSessions();
        }
      });
    }
  }

  uploadDocument(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || this.isLoading()) {
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
              this.appendMessage({
                sender: 'bot',
                text: response.message
              });
              this.loadSessions();
            }
          },
          error: (error) => this.appendMessage({ sender: 'bot', text: this.errorMessage(error, 'Document upload failed.') })
        });
    });
  }

  ingestUrl(): void {
    const url = this.urlInput.trim();
    if (!url || this.isLoading()) {
      return;
    }

    this.urlInput = '';
    this.isLoading.set(true);
    this.ensureSessionThen((sessionId) => {
      this.chatService.ingestUrl(url, sessionId, this.privateMode())
        .pipe(finalize(() => this.isLoading.set(false)))
        .subscribe({
          next: (response) => {
            this.appendMessage({
              sender: 'bot',
              text: response.message
            });
            this.loadSessions();
          },
          error: (error) => this.appendMessage({ sender: 'bot', text: this.errorMessage(error, 'URL could not be read.') })
        });
    });
  }

  toggleTheme(): void {
    this.darkMode.update((value) => !value);
  }

  onUserInputChange(value: string): void {
    this.inputChanges.next(value);
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
        this.sessions.set(sessions);
        if (!this.currentSessionId() && sessions.length > 0) {
          this.selectSession(sessions[0]);
        }
        if (!this.currentSessionId() && sessions.length === 0 && !this.initialSessionCreationInFlight) {
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

    this.chatService.createSession(this.privateMode()).subscribe({
      next: (session) => {
        this.currentSessionId.set(session.id);
        this.privateMode.set(session.privateMode);
        callback(session.id);
      },
      error: () => {
        this.isLoading.set(false);
        this.appendMessage({ sender: 'bot', text: 'Unable to create chat session.' });
      }
    });
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

  private appendMessage(message: ChatMessage): number {
    const index = this.messages().length;
    this.messages.update((messages) => [...messages, message]);
    this.shouldScrollMessages = true;
    return index;
  }

  private updateMessage(index: number, updater: (message: ChatMessage) => ChatMessage): void {
    this.messages.update((messages) => messages.map((message, messageIndex) => (
      messageIndex === index ? updater(message) : message
    )));
    this.shouldScrollMessages = true;
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
