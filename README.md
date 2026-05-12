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

## Optional Ollama LLM

Ollama is disabled by default so the chatbot keeps using stored database answers.

To enable Ollama, start Ollama locally and update `backend/src/main/resources/application.properties`:

```properties
ollama.enabled=true
ollama.url=http://localhost:11434/api/generate
ollama.model=llama3.2
```

When enabled, the backend asks Ollama first. If Ollama is disabled or unavailable, it falls back to the existing database Q&A matching.

## Run Frontend

```bash
cd frontend
npm install
npm start
```

Frontend URL: `http://localhost:4200`

## API

```http
POST /api/chat/ask
Content-Type: application/json

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
