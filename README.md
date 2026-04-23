# Internal Chatbot

Simple Angular + Spring Boot internal chatbot.

## Complete Project Structure

```text
internal-chatbot/
  backend/
    pom.xml
    README.md
    src/main/java/com/example/internalchatbot/
      InternalChatbotApplication.java
      controller/
        ChatController.java
      dto/
        ChatRequest.java
        ChatResponse.java
      entity/
        ChatQuestion.java
      repository/
        ChatQuestionRepository.java
      service/
        ChatService.java
    src/main/resources/
      application.properties
      data.sql
  frontend/
    package.json
    angular.json
    tsconfig.json
    tsconfig.app.json
    src/
      index.html
      main.ts
      styles.css
      app/
        app.ts
        app.html
        app.css
        chat.service.ts
```

## Run Backend

```bash
cd backend
mvn spring-boot:run
```

Backend URL: `http://localhost:8080`

## Run Frontend

```bash
cd frontend
npm install
npm start
```

Frontend URL: `http://localhost:4200`

## API

Login:

```http
POST /api/auth/login
Content-Type: application/json

{
  "email": "admin@td.com",
  "password": "admin123"
}
```

Ask chatbot:

```http
POST /api/chat/ask
Content-Type: application/json
X-User-Token: token-from-login

{
  "message": "How to create RITM?"
}
```

Response:

```json
{
  "reply": "Steps to create RITM: 1. Open the service portal..."
}
```

## Phase 2 Improvements

1. AI NLP search with embeddings or semantic similarity.
2. Authentication with Spring Security and JWT.
3. Chat history stored by user and timestamp.
4. Admin panel to create, update, and delete Q&A records.

## Current Demo Logins

- Admin: `admin@td.com` / `admin123`
- User: `user@td.com` / `user123`

Only valid `@td.com` emails are accepted. Admin users can grant access to more `@td.com` users and add top-page announcements.

## Logs

Backend logs are written to `backend/system.out.logs`. Chat requests over 10 seconds are written as error logs. Frontend timeout errors are also reported back to the backend log endpoint when possible.
