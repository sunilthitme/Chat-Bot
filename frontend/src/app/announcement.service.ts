import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface AnnouncementResponse {
  message: string;
}

// Service keeps announcement API calls in one place.
@Injectable({
  providedIn: 'root'
})
export class AnnouncementService {
  private readonly apiUrl = 'http://localhost:8080/api/announcements';

  constructor(private readonly http: HttpClient) {}

  getActive(): Observable<AnnouncementResponse> {
    return this.http.get<AnnouncementResponse>(`${this.apiUrl}/active`);
  }

  add(message: string, token: string): Observable<AnnouncementResponse> {
    return this.http.post<AnnouncementResponse>(
      this.apiUrl,
      { message },
      {
        headers: {
          'X-User-Token': token
        }
      }
    );
  }
}
