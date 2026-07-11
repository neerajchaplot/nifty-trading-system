import { Component, OnInit, inject } from '@angular/core';
import { IonApp, IonRouterOutlet } from '@ionic/angular/standalone';
import { AgentUserService } from './core/services/agent-user.service';
import { UserStateService } from './core/services/user-state.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [IonApp, IonRouterOutlet],
  template: `<ion-app><ion-router-outlet /></ion-app>`,
})
export class AppComponent implements OnInit {
  private agentUser = inject(AgentUserService);
  private userState = inject(UserStateService);

  ngOnInit(): void {
    this.agentUser.me().subscribe({
      next: profile => this.userState.setProfile(profile),
      error: err => console.error('Failed to load user profile', err),
    });
  }
}
