import { Component, Input } from '@angular/core';
import { IonHeader, IonToolbar, IonTitle, IonButtons, IonMenuButton } from '@ionic/angular/standalone';

/** Shared page header: hamburger (opens the left drawer) + title. */
@Component({
  selector: 'app-header',
  standalone: true,
  imports: [IonHeader, IonToolbar, IonTitle, IonButtons, IonMenuButton],
  template: `
    <ion-header>
      <ion-toolbar style="--background:#ffffff; --border-color:#E2E8F0;">
        <ion-buttons slot="start">
          <ion-menu-button style="--color:#1B4FA8;"></ion-menu-button>
        </ion-buttons>
        <ion-title style="color:#1B4FA8;">{{ title }}</ion-title>
      </ion-toolbar>
    </ion-header>
  `,
})
export class AppHeaderComponent {
  @Input() title = '';
}
