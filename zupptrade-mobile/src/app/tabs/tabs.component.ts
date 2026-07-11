import { Component } from '@angular/core';
import {
  IonTabs, IonTabBar, IonTabButton, IonIcon, IonLabel,
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { analyticsOutline, swapHorizontalOutline, pulseOutline, documentTextOutline } from 'ionicons/icons';

@Component({
  selector: 'app-tabs',
  standalone: true,
  imports: [IonTabs, IonTabBar, IonTabButton, IonIcon, IonLabel],
  template: `
    <ion-tabs>
      <ion-tab-bar slot="bottom">

        <ion-tab-button tab="signal">
          <ion-icon name="analytics-outline" />
          <ion-label>Signal</ion-label>
        </ion-tab-button>

        <ion-tab-button tab="trade">
          <ion-icon name="swap-horizontal-outline" />
          <ion-label>Trade</ion-label>
        </ion-tab-button>

        <ion-tab-button tab="monitor">
          <ion-icon name="pulse-outline" />
          <ion-label>Monitor</ion-label>
        </ion-tab-button>

        <ion-tab-button tab="audit">
          <ion-icon name="document-text-outline" />
          <ion-label>Audit</ion-label>
        </ion-tab-button>

      </ion-tab-bar>
    </ion-tabs>
  `,
})
export class TabsComponent {
  constructor() {
    addIcons({ analyticsOutline, swapHorizontalOutline, pulseOutline, documentTextOutline });
  }
}
