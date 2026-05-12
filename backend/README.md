# Internal Chatbot Backend

Simple Spring Boot REST API for an internal chatbot that answers questions from database records.

## Run

```bash
mvn spring-boot:run
```

## Optional Ollama LLM

Ollama is disabled by default. Enable it in `src/main/resources/application.properties`:

```properties
ollama.enabled=true
ollama.url=http://localhost:11434/api/generate
ollama.model=llama3.2
```

When enabled, chat requests are sent to Ollama first. If Ollama is disabled or unavailable, the backend uses the stored Q&A answers.

## Ask API

```http
POST http://localhost:8080/api/chat/ask
Content-Type: application/json

{
  "message": "How to create RITM?"
}
```

## H2 Console

Open `http://localhost:8080/h2-console`.

- JDBC URL: `jdbc:h2:mem:chatbotdb`
- User: `sa`
- Password: leave empty

## MySQL Later

Replace the H2 datasource properties in `src/main/resources/application.properties` with:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/internal_chatbot
spring.datasource.username=root
spring.datasource.password=your_password
spring.jpa.hibernate.ddl-auto=update
```
