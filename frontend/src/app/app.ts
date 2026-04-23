import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { ChatService } from './chat.service';

type Sender = 'user' | 'bot';

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
export class AppComponent {
  userInput = '';
  isLoading = false;

  messages: ChatMessage[] = [
    {
      sender: 'bot',
      text: 'Hi, ask me about application features or processes.'
    }
  ];

  constructor(private readonly chatService: ChatService) {}

  sendMessage(): void {
    const message = this.userInput.trim();

    if (!message || this.isLoading) {
      return;
    }

    this.messages.push({ sender: 'user', text: message });
    this.userInput = '';
    this.isLoading = true;

    this.chatService.ask(message)
      .pipe(finalize(() => (this.isLoading = false)))
      .subscribe({
        next: (response) => {
          this.messages.push({ sender: 'bot', text: response.reply });
        },
        error: () => {
          this.messages.push({
            sender: 'bot',
            text: 'Backend is not reachable. Please make sure Spring Boot is running.'
          });
        }
      });
  }
}
