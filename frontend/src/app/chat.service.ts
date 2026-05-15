import { HttpClient } from '@angular/common/http';
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

export interface IncidentAnalysisResponse {
  sessionId: string;
  analysis: string;
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

  uploadDocument(file: File, sessionId: string, privateMode: boolean): Observable<UploadResponse> {
    const body = new FormData();
    body.append('file', file);
    body.append('sessionId', sessionId);
    body.append('userKey', 'local-user');
    body.append('privateMode', String(privateMode));
    return this.http.post<UploadResponse>(`${this.baseUrl}/knowledge/documents`, body);
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

  analyzeIncident(incidentDetails: string, sessionId: string, privateMode: boolean): Observable<IncidentAnalysisResponse> {
    return this.http.post<IncidentAnalysisResponse>(`${this.baseUrl}/incidents/analyze`, {
      incidentDetails,
      sessionId,
      userKey: 'local-user',
      privateMode
    });
  }
}
