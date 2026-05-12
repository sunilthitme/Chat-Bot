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

Chat requests always check the internal database first.

- If the database has a matching answer and Ollama is enabled, Ollama rewrites that stored answer into a more helpful final response.
- If the database has a matching answer and Ollama is disabled or unavailable, the stored answer is returned.
- If the database has no matching answer and Ollama is enabled, Ollama answers directly.
- If the database has no matching answer and Ollama is disabled or unavailable, the default not-found response is returned.

## Ask API

```http
POST http://localhost:8080/api/chat/ask
Content-Type: application/json

{
  "message": "How to create RITM?"
}
```

## Direct LLM API

```http
POST http://localhost:8080/api/llm/generate
Content-Type: application/json

{
  "message": "Explain password reset steps"
}
```

This endpoint returns `503 Service Unavailable` when `ollama.enabled=false` or Ollama cannot be reached.

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
