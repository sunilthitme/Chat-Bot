import { CommonModule } from '@angular/common';
import { AfterViewChecked, Component, ElementRef, OnInit, ViewChild, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { ChatService, ChatSession, SourceReference } from './chat.service';

type Sender = 'user' | 'bot';

interface ChatMessage {
  sender: Sender;
  text: string;
  sources?: SourceReference[];
}

// Root component renders the chatbot window and handles user input.
@Component({
  selector: 'app-root',
  imports: [CommonModule, FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class AppComponent implements AfterViewChecked, OnInit {
  userInput = '';
  urlInput = '';
  incidentInput = '';
  readonly isLoading = signal(false);
  readonly isSidebarLoading = signal(false);
  readonly privateMode = signal(false);
  readonly sessions = signal<ChatSession[]>([]);
  readonly currentSessionId = signal('');

  readonly messages = signal<ChatMessage[]>([
    {
      sender: 'bot',
      text: 'Hi, ask me about application features or processes.'
    }
  ]);

  @ViewChild('messagesContainer')
  private messagesContainer?: ElementRef<HTMLElement>;

  private shouldScrollMessages = true;

  constructor(private readonly chatService: ChatService) {}

  ngOnInit(): void {
    this.loadSessions();
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

    if (!message || this.isLoading()) {
      return;
    }

    this.appendMessage({ sender: 'user', text: message });
    this.userInput = '';
    this.isLoading.set(true);

    this.chatService.ask({
      message,
      sessionId: this.currentSessionId(),
      userKey: 'local-user',
      privateMode: this.privateMode()
    })
      .pipe(finalize(() => this.isLoading.set(false)))
      .subscribe({
        next: (response) => {
          this.currentSessionId.set(response.sessionId);
          this.privateMode.set(response.privateMode);
          this.appendMessage({ sender: 'bot', text: response.reply, sources: response.sources });
          this.loadSessions();
        },
        error: () => {
          this.appendMessage({
            sender: 'bot',
            text: 'Backend is not reachable. Please make sure Spring Boot is running.'
          });
        }
      });
  }

  newChat(): void {
    this.isSidebarLoading.set(true);
    this.chatService.createSession(this.privateMode())
      .pipe(finalize(() => this.isSidebarLoading.set(false)))
      .subscribe({
        next: (session) => {
          this.currentSessionId.set(session.id);
          this.privateMode.set(session.privateMode);
          this.messages.set([{
            sender: 'bot',
            text: 'New chat started. Ask me about internal knowledge, documents, URLs, or incidents.'
          }]);
          this.loadSessions();
        }
      });
  }

  selectSession(session: ChatSession): void {
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
    this.ensureSessionThen((sessionId) => {
      this.chatService.uploadDocument(file, sessionId, this.privateMode())
        .pipe(finalize(() => this.isLoading.set(false)))
        .subscribe({
          next: (response) => {
            this.currentSessionId.set(response.sessionId);
            this.appendMessage({
              sender: 'bot',
              text: `${response.message} Chunks stored: ${response.chunksStored}.`
            });
            this.loadSessions();
          },
          error: () => this.appendMessage({ sender: 'bot', text: 'Document upload failed.' })
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
            this.currentSessionId.set(response.sessionId);
            this.appendMessage({
              sender: 'bot',
              text: `URL ingested. Chunks stored: ${response.chunksStored}.\n\n${response.summary}`
            });
            this.loadSessions();
          },
          error: () => this.appendMessage({ sender: 'bot', text: 'URL could not be read. Check the allow-list and URL access.' })
        });
    });
  }

  analyzeIncident(): void {
    const incidentDetails = this.incidentInput.trim();
    if (!incidentDetails || this.isLoading()) {
      return;
    }

    this.incidentInput = '';
    this.isLoading.set(true);
    this.ensureSessionThen((sessionId) => {
      this.chatService.analyzeIncident(incidentDetails, sessionId, this.privateMode())
        .pipe(finalize(() => this.isLoading.set(false)))
        .subscribe({
          next: (response) => {
            this.currentSessionId.set(response.sessionId);
            this.appendMessage({ sender: 'user', text: `Incident details:\n${incidentDetails}` });
            this.appendMessage({ sender: 'bot', text: response.analysis });
            this.loadSessions();
          },
          error: () => this.appendMessage({ sender: 'bot', text: 'Incident analysis failed.' })
        });
    });
  }

  private loadSessions(): void {
    this.chatService.listSessions().subscribe({
      next: (sessions) => {
        this.sessions.set(sessions);
        if (!this.currentSessionId() && sessions.length > 0) {
          this.selectSession(sessions[0]);
        }
        if (!this.currentSessionId() && sessions.length === 0) {
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

  private appendMessage(message: ChatMessage): void {
    this.messages.update((messages) => [...messages, message]);
    this.shouldScrollMessages = true;
  }
}
