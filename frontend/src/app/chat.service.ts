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
  private readonly chatUrl = 'http://localhost:8080/api/chat/ask';
  private readonly llmUrl = 'http://localhost:8080/api/llm/generate';

  constructor(private readonly http: HttpClient) {}

  ask(message: string): Observable<ChatResponse> {
    const request: ChatRequest = { message };
    return this.http.post<ChatResponse>(this.chatUrl, request);
  }

  askLlm(message: string): Observable<ChatResponse> {
    const request: ChatRequest = { message };
    return this.http.post<ChatResponse>(this.llmUrl, request);
  }
}
