import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { AppComponent } from './app/app';

// Starts the Angular app and registers HttpClient for REST API calls.
bootstrapApplication(AppComponent, {
  providers: [provideHttpClient()]
}).catch((error) => console.error(error));
