import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type UserRole = 'ADMIN' | 'USER';

export interface LoginResponse {
  token: string;
  email: string;
  role: UserRole;
}

export interface UserAccessResponse {
  email: string;
  role: UserRole;
  active: boolean;
}

// Service keeps login and admin access API calls in one place.
@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly apiUrl = 'http://localhost:8080/api/auth';

  constructor(private readonly http: HttpClient) {}

  login(email: string, password: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${this.apiUrl}/login`, { email, password });
  }

  grantAccess(email: string, temporaryPassword: string, token: string): Observable<UserAccessResponse> {
    return this.http.post<UserAccessResponse>(
      `${this.apiUrl}/grant-access`,
      { email, temporaryPassword },
      {
        headers: {
          'X-User-Token': token
        }
      }
    );
  }
}
