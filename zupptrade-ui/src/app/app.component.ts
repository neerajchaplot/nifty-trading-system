import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * Thin router host. The authenticated shell lives in ShellComponent at the guarded '/' route;
 * '/login' and '/auth/callback' render outside the guard.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  template: `<router-outlet></router-outlet>`,
})
export class AppComponent {}
