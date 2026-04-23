import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize, timeout } from 'rxjs';
import { AnnouncementService } from './announcement.service';
import { AuthService, LoginResponse, UserRole } from './auth.service';
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
  private readonly chatTimeoutMs = 10_000;
  private readonly loginTimeoutMs = 5_000;
  private readonly loginUrl = 'http://localhost:8080/api/auth/login';
  private loginAttemptId = 0;
  private loginAbortController: AbortController | null = null;

  userInput = '';
  loginEmail = '';
  loginPassword = '';
  accessEmail = '';
  accessPassword = '';
  announcementInput = '';
  announcement = '';
  loginError = '';
  adminMessage = '';
  isLoading = false;
  isLoggingIn = false;
  isSavingAdminAction = false;
  token = localStorage.getItem('chatbotToken') || '';
  email = localStorage.getItem('chatbotEmail') || '';
  role = (localStorage.getItem('chatbotRole') as UserRole | null) || null;

  messages: ChatMessage[] = [
    {
      sender: 'bot',
      text: 'Hi, ask me about application features or processes.'
    }
  ];

  constructor(
    private readonly chatService: ChatService,
    private readonly authService: AuthService,
    private readonly announcementService: AnnouncementService
  ) {
    this.loadAnnouncement();
  }

  get isLoggedIn(): boolean {
    return !!this.token;
  }

  get isAdmin(): boolean {
    return this.role === 'ADMIN';
  }

  async login(): Promise<void> {
    this.loginError = '';
    const email = this.loginEmail.trim().toLowerCase();
    const password = this.loginPassword;

    if (!this.isValidTdEmail(email)) {
      this.loginError = 'Please enter a valid td.com email address.';
      return;
    }

    if (!password) {
      this.loginError = 'Password is required.';
      return;
    }

    const currentAttemptId = ++this.loginAttemptId;
    this.loginAbortController?.abort();
    this.loginAbortController = new AbortController();
    this.isLoggingIn = true;

    const timeoutId = window.setTimeout(() => {
      if (this.isLoggingIn && this.loginAttemptId === currentAttemptId) {
        this.loginAbortController?.abort();
        this.isLoggingIn = false;
        this.loginError = 'Login took too long. Restart backend and refresh this page.';
        this.chatService.logClientError(`Login UI timeout after ${this.loginTimeoutMs} ms for email: ${email}`);
      }
    }, this.loginTimeoutMs);

    try {
      const response = await fetch(this.loginUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ email, password }),
        signal: this.loginAbortController.signal
      });

      if (this.loginAttemptId !== currentAttemptId) {
        return;
      }

      if (!response.ok) {
        this.loginError = 'Login failed. Use a valid td.com account with access.';
        return;
      }

      const loginResponse = await response.json() as LoginResponse;
      this.saveSession(loginResponse);
    } catch (error) {
      if (this.loginAttemptId !== currentAttemptId) {
        return;
      }

      const isAbort = error instanceof DOMException && error.name === 'AbortError';
      this.loginError = isAbort
        ? 'Login request was cancelled or timed out. Please try again.'
        : 'Backend is not reachable. Please make sure Spring Boot is running.';
    } finally {
      window.clearTimeout(timeoutId);
      if (this.loginAttemptId === currentAttemptId) {
        this.isLoggingIn = false;
        this.loginAbortController = null;
      }
    }
  }

  cancelLogin(): void {
    if (!this.isLoggingIn) {
      return;
    }

    this.loginAttemptId++;
    this.loginAbortController?.abort();
    this.loginAbortController = null;
    this.isLoggingIn = false;
    this.loginError = 'Login cancelled. Please try again.';
  }

  logout(): void {
    this.token = '';
    this.email = '';
    this.role = null;
    localStorage.removeItem('chatbotToken');
    localStorage.removeItem('chatbotEmail');
    localStorage.removeItem('chatbotRole');
  }

  sendMessage(): void {
    const message = this.userInput.trim();

    if (!message || this.isLoading || !this.token) {
      return;
    }

    this.messages.push({ sender: 'user', text: message });
    this.userInput = '';
    this.isLoading = true;

    this.chatService.ask(message, this.token)
      .pipe(timeout(this.chatTimeoutMs))
      .pipe(finalize(() => (this.isLoading = false)))
      .subscribe({
        next: (response) => {
          this.messages.push({ sender: 'bot', text: response.reply });
        },
        error: (error) => {
          const isTimeout = error?.name === 'TimeoutError';
          if (isTimeout) {
            this.chatService.logClientError(`Chat response exceeded 10 seconds for question: ${message}`);
          }
          this.messages.push({
            sender: 'bot',
            text: isTimeout
              ? 'Response is taking more than 10 seconds. Please try again.'
              : 'Backend is not reachable. Please make sure Spring Boot is running.'
          });
        }
      });
  }

  grantAccess(): void {
    this.adminMessage = '';
    const email = this.accessEmail.trim().toLowerCase();

    if (!this.isValidTdEmail(email)) {
      this.adminMessage = 'Access can be granted only to a valid td.com email.';
      return;
    }

    this.isSavingAdminAction = true;
    this.authService.grantAccess(email, this.accessPassword, this.token)
      .pipe(finalize(() => (this.isSavingAdminAction = false)))
      .subscribe({
        next: () => {
          this.adminMessage = `Access granted to ${email}.`;
          this.accessEmail = '';
          this.accessPassword = '';
        },
        error: () => {
          this.adminMessage = 'Unable to grant access. Please check admin login.';
        }
      });
  }

  addAnnouncement(): void {
    this.adminMessage = '';
    const message = this.announcementInput.trim();

    if (!message) {
      this.adminMessage = 'Announcement message is required.';
      return;
    }

    this.isSavingAdminAction = true;
    this.announcementService.add(message, this.token)
      .pipe(finalize(() => (this.isSavingAdminAction = false)))
      .subscribe({
        next: (response) => {
          this.announcement = response.message;
          this.announcementInput = '';
          this.adminMessage = 'Announcement added.';
        },
        error: () => {
          this.adminMessage = 'Unable to add announcement. Please check admin login.';
        }
      });
  }

  private loadAnnouncement(): void {
    this.announcementService.getActive().subscribe({
      next: (response) => {
        this.announcement = response.message;
      },
      error: () => {
        this.chatService.logClientError('Unable to load active announcement');
      }
    });
  }

  private saveSession(response: LoginResponse): void {
    this.token = response.token;
    this.email = response.email;
    this.role = response.role;
    localStorage.setItem('chatbotToken', response.token);
    localStorage.setItem('chatbotEmail', response.email);
    localStorage.setItem('chatbotRole', response.role);
    this.loginEmail = '';
    this.loginPassword = '';
  }

  private isValidTdEmail(email: string): boolean {
    return /^[^\s@]+@td\.com$/i.test(email);
  }
}
