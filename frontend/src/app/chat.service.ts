import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface ChatRequest {
  message: string;
}

export interface ChatResponse {
  reply: string;
}

// Service keeps all backend API communication in one place.
@Injectable({
  providedIn: 'root'
})
export class ChatService {
  private readonly apiUrl = 'http://localhost:8080/api/chat/ask';
  private readonly clientLogUrl = 'http://localhost:8080/api/logs/client-error';

  constructor(private readonly http: HttpClient) {}

  ask(message: string, token: string): Observable<ChatResponse> {
    const request: ChatRequest = { message };
    return this.http.post<ChatResponse>(this.apiUrl, request, {
      headers: {
        'X-User-Token': token
      }
    });
  }

  logClientError(message: string): void {
    this.http.post(this.clientLogUrl, { message }).subscribe({
      error: () => console.error('Unable to send client log to backend')
    });
  }
}
