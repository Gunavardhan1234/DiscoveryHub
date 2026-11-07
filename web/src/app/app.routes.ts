import { Routes } from '@angular/router';

/** Routes with lazy-loaded standalone feature components. Each chunk loads on
 *  first navigation, keeping the initial bundle small. */
export const routes: Routes = [
  {path: 'dashboard', loadComponent: () => import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent)},
  {path: 'search', loadComponent: () => import('./features/search/search.component').then((m) => m.SearchComponent)},
  {path: 'cases', loadComponent: () => import('./features/cases/cases.component').then((m) => m.CasesComponent)},
  {path: 'cases/:id', loadComponent: () => import('./features/cases/case-detail.component').then((m) => m.CaseDetailComponent)},
  {path: 'holds', loadComponent: () => import('./features/holds/holds.component').then((m) => m.HoldsComponent)},
  {path: 'exports', loadComponent: () => import('./features/exports/exports.component').then((m) => m.ExportsComponent)},
  {path: 'audit', loadComponent: () => import('./features/audit/audit.component').then((m) => m.AuditComponent)},
  {path: 'disposition', loadComponent: () => import('./features/disposition/disposition.component').then((m) => m.DispositionComponent)},
  {path: '', pathMatch: 'full', redirectTo: 'dashboard'},
  {path: '**', redirectTo: 'dashboard'},
];
