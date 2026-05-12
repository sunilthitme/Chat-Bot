import { CommonModule } from '@angular/common';
import { AfterViewChecked, Component, ElementRef, ViewChild, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { ChatService } from './chat.service';

type Sender = 'user' | 'bot';
type ResponseMode = 'internal' | 'llm';

interface ChatMessage {
  sender: Sender;
  text: string;
}

// Root component renders the chatbot window and handles user input.
@Component({
  selector: 'app-root',
  imports: [CommonModule, FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class AppComponent implements AfterViewChecked {
  userInput = '';
  readonly isLoading = signal(false);
  readonly responseMode = signal<ResponseMode>('internal');

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

    const response = this.responseMode() === 'llm'
      ? this.chatService.askLlm(message)
      : this.chatService.ask(message);

    response
      .pipe(finalize(() => this.isLoading.set(false)))
      .subscribe({
        next: (response) => {
          this.appendMessage({ sender: 'bot', text: response.reply });
        },
        error: () => {
          this.appendMessage({
            sender: 'bot',
            text: this.responseMode() === 'llm'
              ? 'Ollama is disabled or unavailable. Enable it in backend properties or switch to Internal mode.'
              : 'Backend is not reachable. Please make sure Spring Boot is running.'
          });
        }
      });
  }

  setResponseMode(mode: ResponseMode): void {
    if (!this.isLoading()) {
      this.responseMode.set(mode);
    }
  }

  placeholderText(): string {
    return this.responseMode() === 'llm'
      ? 'Ask Ollama directly'
      : 'Ask: How to create RITM?';
  }

  private appendMessage(message: ChatMessage): void {
    this.messages.update((messages) => [...messages, message]);
    this.shouldScrollMessages = true;
  }
}
