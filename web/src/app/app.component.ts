import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { LoadingSpinnerComponent } from './shared/loading-spinner/loading-spinner.component';
import { ErrorToastComponent } from './shared/error-toast/error-toast.component';
import { environment } from '../environments/environment';

interface NavItem { path: string; label: string; }

const NAV: NavItem[] = [
  { path: '/dashboard', label: 'Dashboard' },
  { path: '/search', label: 'Search' },
  { path: '/cases', label: 'Cases' },
  { path: '/holds', label: 'Holds' },
  { path: '/exports', label: 'Exports' },
  { path: '/disposition', label: 'Disposition' },
  { path: '/audit', label: 'Audit' },
];

/** Shared application shell (architecture §7): sticky header with nav,
 *  <router-outlet/> for feature screens, global loading spinner + error toast.
 *  RouterLinkActive lights the current tab. */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, LoadingSpinnerComponent, ErrorToastComponent],
  template: `
    <header class="shell-header">
      <h1>DiscoveryHub</h1>
      <nav>
        @for (item of nav; track item.path) {
          <a [routerLink]="item.path" routerLinkActive="active">{{ item.label }}</a>
        }
      </nav>
      <span class="spacer"></span>
      <span class="env-tag">{{ env() }}</span>
    </header>
    <main>
      <section class="shell-content"><router-outlet /></section>
      <footer class="shell-footer">DiscoveryHub — e-discovery & legal hold platform. Built with Angular {{ ang }}.</footer>
    </main>
    <app-loading-spinner />
    <app-error-toast />
  `,
})
export class AppComponent {
  readonly nav = NAV;
  readonly env = () => environment.production ? 'prod' : 'dev';
  readonly ang = '19';
}
