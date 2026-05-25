import { Injectable } from '@angular/core';
import { ChatSession } from './chat.service';

@Injectable({
  providedIn: 'root'
})
export class ChatSessionManagerService {
  private readonly hiddenSessionsKey = 'td-chatbot.hidden-session-ids';
  private readonly nextChatNumberKey = 'td-chatbot.next-chat-number';

  nextChatTitle(existingSessions: ChatSession[]): string {
    const storedNext = this.readNumber(this.nextChatNumberKey, 1);
    const nextFromExisting = existingSessions
      .map((session) => /^New Chat (\d+)$/i.exec(session.title.trim())?.[1])
      .filter((value): value is string => Boolean(value))
      .map((value) => Number(value))
      .filter((value) => Number.isFinite(value))
      .reduce((highest, value) => Math.max(highest, value + 1), 1);
    const next = Math.max(storedNext, nextFromExisting);
    this.writeNumber(this.nextChatNumberKey, next + 1);
    return `New Chat ${next}`;
  }

  createPrivateSession(title: string): ChatSession {
    const now = new Date().toISOString();
    return {
      id: `private-${this.randomId()}`,
      title,
      privateMode: true,
      createdAt: now,
      updatedAt: now
    };
  }

  visibleSessions(sessions: ChatSession[]): ChatSession[] {
    const hiddenIds = this.hiddenSessionIds();
    return sessions.filter((session) => !hiddenIds.has(session.id));
  }

  hideSession(sessionId: string): void {
    const hiddenIds = this.hiddenSessionIds();
    hiddenIds.add(sessionId);
    this.writeStringList(this.hiddenSessionsKey, [...hiddenIds]);
  }

  isPrivateSessionId(sessionId: string): boolean {
    return sessionId.startsWith('private-');
  }

  private hiddenSessionIds(): Set<string> {
    return new Set(this.readStringList(this.hiddenSessionsKey));
  }

  private readStringList(key: string): string[] {
    try {
      const value = sessionStorage.getItem(key);
      return value ? JSON.parse(value) as string[] : [];
    } catch {
      return [];
    }
  }

  private writeStringList(key: string, value: string[]): void {
    try {
      sessionStorage.setItem(key, JSON.stringify(value));
    } catch {
      // Session-only hiding is best-effort when browser storage is unavailable.
    }
  }

  private readNumber(key: string, fallback: number): number {
    try {
      const value = Number(localStorage.getItem(key));
      return Number.isFinite(value) && value > 0 ? value : fallback;
    } catch {
      return fallback;
    }
  }

  private writeNumber(key: string, value: number): void {
    try {
      localStorage.setItem(key, String(value));
    } catch {
      // Continuous numbering still works in memory for the current click.
    }
  }

  private randomId(): string {
    if ('randomUUID' in crypto) {
      return crypto.randomUUID();
    }
    return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }
}
