import { Injectable } from '@angular/core';
import { UserProfile } from '../models/user-profile.model';

@Injectable({ providedIn: 'root' })
export class UserStateService {
  private _profile: UserProfile | null = null;

  get profile(): UserProfile | null { return this._profile; }
  get userProfileId(): string | null { return this._profile?.id ?? null; }

  setProfile(profile: UserProfile): void { this._profile = profile; }
}
