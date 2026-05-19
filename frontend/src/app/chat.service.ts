import { HttpClient, HttpEvent } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface ChatRequest {
  message: string;
  sessionId?: string;
  userKey?: string;
  privateMode?: boolean;
}

export interface SourceReference {
  sourceName: string;
  sourceType: string;
  sourceUrl?: string;
  pageNumber?: number;
  sectionTitle?: string;
  score: number;
  preview: string;
}

export interface ChatResponse {
  sessionId: string;
  reply: string;
  privateMode: boolean;
  sources: SourceReference[];
}

export interface ChatSession {
  id: string;
  title: string;
  privateMode: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface StoredMessage {
  id: number;
  role: string;
  content: string;
  createdAt: string;
}

export interface UploadResponse {
  documentId: number | null;
  sessionId: string;
  sourceName: string;
  chunksStored: number;
  privateMode: boolean;
  message: string;
}

export interface UrlIngestResponse {
  documentId: number | null;
  sessionId: string;
  url: string;
  chunksStored: number;
  privateMode: boolean;
  summary: string;
}

export type StreamingChatEvent =
  | { type: 'token'; token: string }
  | { type: 'done'; response: ChatResponse };

interface ParsedSseEvent {
  event: string;
  data: string;
}

// Service keeps all backend API communication in one place.
@Injectable({
  providedIn: 'root'
})
export class ChatService {
  private readonly baseUrl = 'http://localhost:8080/api';

  constructor(private readonly http: HttpClient) {}

  ask(request: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${this.baseUrl}/chat/ask`, request);
  }

  streamAsk(request: ChatRequest): Observable<StreamingChatEvent> {
    return new Observable<StreamingChatEvent>((observer) => {
      const controller = new AbortController();
      fetch(`${this.baseUrl}/chat/ask/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
        signal: controller.signal
      })
        .then(async (response) => {
          if (!response.ok || !response.body) {
            throw new Error(`Streaming request failed with status ${response.status}`);
          }

          const reader = response.body.getReader();
          const decoder = new TextDecoder();
          let buffer = '';
          while (true) {
            const { value, done } = await reader.read();
            if (done) {
              break;
            }
            buffer += decoder.decode(value, { stream: true });
            const parts = buffer.split('\n\n');
            buffer = parts.pop() ?? '';
            for (const part of parts) {
              const parsed = this.parseSse(part);
              if (parsed.event === 'token') {
                observer.next({ type: 'token', token: parsed.data });
              }
              if (parsed.event === 'done') {
                observer.next({ type: 'done', response: JSON.parse(parsed.data) as ChatResponse });
              }
            }
          }
          if (!observer.closed) {
            observer.complete();
          }
        })
        .catch((error) => {
          if (!observer.closed) {
            observer.error(error);
          }
        });

      return () => controller.abort();
    });
  }

  createSession(privateMode: boolean): Observable<ChatSession> {
    return this.http.post<ChatSession>(`${this.baseUrl}/sessions`, {
      title: 'New chat',
      userKey: 'local-user',
      privateMode
    });
  }

  listSessions(): Observable<ChatSession[]> {
    return this.http.get<ChatSession[]>(`${this.baseUrl}/sessions?userKey=local-user`);
  }

  listMessages(sessionId: string): Observable<StoredMessage[]> {
    return this.http.get<StoredMessage[]>(`${this.baseUrl}/sessions/${sessionId}/messages`);
  }

  setPrivateMode(sessionId: string, privateMode: boolean): Observable<ChatSession> {
    return this.http.patch<ChatSession>(`${this.baseUrl}/sessions/${sessionId}/private-mode`, { privateMode });
  }

  uploadDocument(file: File, sessionId: string, privateMode: boolean): Observable<HttpEvent<UploadResponse>> {
    const body = new FormData();
    body.append('file', file);
    body.append('sessionId', sessionId);
    body.append('userKey', 'local-user');
    body.append('privateMode', String(privateMode));
    return this.http.post<UploadResponse>(`${this.baseUrl}/knowledge/documents`, body, {
      observe: 'events',
      reportProgress: true
    });
  }

  ingestUrl(url: string, sessionId: string, privateMode: boolean): Observable<UrlIngestResponse> {
    return this.http.post<UrlIngestResponse>(`${this.baseUrl}/knowledge/urls`, {
      url,
      sessionId,
      userKey: 'local-user',
      privateMode,
      loginRequired: false
    });
  }

  private parseSse(raw: string): ParsedSseEvent {
    const lines = raw.split('\n');
    let event = 'message';
    const data: string[] = [];
    for (const line of lines) {
      if (line.startsWith('event:')) {
        event = line.substring('event:'.length).trim();
      }
      if (line.startsWith('data:')) {
        data.push(line.substring('data:'.length).trimStart());
      }
    }
    return { event, data: data.join('\n') };
  }
}
