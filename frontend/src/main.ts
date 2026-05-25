import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { AppComponent } from './app/app';

// Starts the Angular app and registers HttpClient for REST API calls.
bootstrapApplication(AppComponent, {
  providers: [provideHttpClient(), provideAnimationsAsync()]
}).catch((error) => console.error(error));
