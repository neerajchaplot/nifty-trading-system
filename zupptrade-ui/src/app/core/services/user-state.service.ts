import { Injectable } from '@angular/core';
import { UserProfile } from '../models/user-profile.model';

/**
 * Holds the resolved user profile for the current session.
 * Populated by AppComponent on startup via AgentUserService.
 * Consumed by any component that needs userProfileId (e.g. RecommendationComponent).
 */
@Injectable({ providedIn: 'root' })
export class UserStateService {
  private _profile: UserProfile | null = null;

  get profile(): UserProfile | null {
    return this._profile;
  }

  get userProfileId(): string | null {
    return this._profile?.id ?? null;
  }

  setProfile(profile: UserProfile): void {
    this._profile = profile;
  }
}
