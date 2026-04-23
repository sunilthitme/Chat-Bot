# Internal Chatbot Backend

Simple Spring Boot REST API for an internal chatbot that answers questions from database records.

## Run

```bash
mvn spring-boot:run
```

## Ask API

First login:

```http
POST http://localhost:8080/api/auth/login
Content-Type: application/json

{
  "email": "admin@td.com",
  "password": "admin123"
}
```

Then ask:

```http
POST http://localhost:8080/api/chat/ask
Content-Type: application/json
X-User-Token: token-from-login

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

## Demo Users

- Admin: `admin@td.com` / `admin123`
- User: `user@td.com` / `user123`

## Logs

All backend logs are written to `system.out.logs` in this backend folder.
