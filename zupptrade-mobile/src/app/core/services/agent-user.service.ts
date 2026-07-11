import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { UpdateUserProfileRequest, UserProfile, UserProfileAuditEntry } from '../models/user-profile.model';

@Injectable({ providedIn: 'root' })
export class AgentUserService {
  private readonly base = environment.agentUserBaseUrl;

  constructor(private http: HttpClient) {}

  me(): Observable<UserProfile> {
    return this.http.get<UserProfile>(`${this.base}/me`);
  }

  updateProfile(profileId: string, request: UpdateUserProfileRequest): Observable<UserProfile> {
    return this.http.put<UserProfile>(`${this.base}/me/profile/${profileId}`, request);
  }

  getAudit(profileId: string): Observable<UserProfileAuditEntry[]> {
    return this.http.get<UserProfileAuditEntry[]>(`${this.base}/me/profile/${profileId}/audit`);
  }
}
