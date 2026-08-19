# PP Portal V2

Pratibha Poshak Portal is a web-based administrative portal for managing applications, students, examinations, users, batches, alumni, and other organizational activities.

This repository contains the React frontend and the current Spring Boot backend.

## Project Structure

```text
pp_portal_v2/
├── client/                         # React Frontend
├── rcf/
│   └── imas-backend/               # Current Spring Boot Backend
├── server/                         # Legacy/previous backend
├── docker-compose.prod.yml
├── package.json
├── package-lock.json
└── README.md
```

> **Current backend:** `rcf/imas-backend` is the active Spring Boot backend. The `server/` directory contains the previous/legacy backend implementation and is not required for the current Spring Boot setup.

## Technology Stack

### Frontend
- React
- React Router
- Axios
- JavaScript
- HTML5
- CSS3

### Backend
- Java 21
- Spring Boot 3.3.5
- Spring Security
- JWT Authentication
- Maven
- Spring JDBC
- Flyway

### Database
- PostgreSQL 17

## Prerequisites

Install:

- Git
- Node.js 20+
- npm
- Java JDK 21
- Maven
- PostgreSQL 17

Check installations:

```bash
git --version
node --version
npm --version
java -version
mvn -version
psql --version
```

## Clone the Repository

```bash
git clone https://github.com/vishalvaddar/pp_portal_v2.git
cd pp_portal_v2
```

## Database Setup

Create the PostgreSQL database:

```sql
CREATE DATABASE pp_portal_db;
```

The default local configuration is:

```text
Host: localhost
Port: 5432
Database: pp_portal_db
User: postgres
```

The application uses the PostgreSQL schema:

```text
pp
```

## Backend Configuration

The Spring Boot backend is located at:

```text
rcf/imas-backend
```

Go to the backend:

```bash
cd rcf/imas-backend
```

Required environment variables:

```text
DB_HOST
DB_PORT
DB_NAME
DB_USER
DB_PASSWORD
JWT_SECRET
```

Example values:

```text
DB_HOST=localhost
DB_PORT=5432
DB_NAME=pp_portal_db
DB_USER=postgres
DB_PASSWORD=your_postgres_password
JWT_SECRET=your_long_random_secret
```

Do not commit real passwords or JWT secrets.

### Windows PowerShell

```powershell
$env:DB_HOST="localhost"
$env:DB_PORT="5432"
$env:DB_NAME="pp_portal_db"
$env:DB_USER="postgres"
$env:DB_PASSWORD="your_postgres_password"
$env:JWT_SECRET="your_long_random_secret"
```

Verify non-sensitive variables:

```powershell
echo $env:DB_HOST
echo $env:DB_PORT
echo $env:DB_NAME
echo $env:DB_USER
```

### Windows CMD

```cmd
set DB_HOST=localhost
set DB_PORT=5432
set DB_NAME=pp_portal_db
set DB_USER=postgres
set DB_PASSWORD=your_postgres_password
set JWT_SECRET=your_long_random_secret
```

## Run the Backend

Open Terminal 1:

```bash
cd pp_portal_v2/rcf/imas-backend
```

Set the environment variables and run:

```bash
mvn spring-boot:run
```

If Maven Wrapper is available on Windows:

```cmd
mvnw.cmd spring-boot:run
```

Backend:

```text
http://localhost:8080
```

## Backend Health Check

Open:

```text
http://localhost:8080/actuator/health
```

Or run:

```bash
curl http://localhost:8080/actuator/health
```

Expected:

```json
{
  "status": "UP"
}
```

## Run the Frontend

Open Terminal 2:

```bash
cd pp_portal_v2/client
```

Install dependencies:

```bash
npm install
```

Start React:

```bash
npm start
```

Frontend:

```text
http://localhost:3000
```

Open:

```text
http://localhost:3000
```

## Run Frontend and Backend Together

### Terminal 1 - Backend

```bash
cd pp_portal_v2/rcf/imas-backend
mvn spring-boot:run
```

Backend:

```text
http://localhost:8080
```

### Terminal 2 - Frontend

```bash
cd pp_portal_v2/client
npm install
npm start
```

Frontend:

```text
http://localhost:3000
```

The frontend communicates with the backend at:

```text
http://localhost:8080
```

Authentication endpoint:

```text
POST http://localhost:8080/api/auth/login
```

## CORS

During local development, the backend allows:

```text
http://localhost:3000
```

If the frontend runs on another port, update the Spring CORS configuration.

## Authentication

The application uses JWT authentication.

Login flow:

```text
React Client
    |
    | POST /api/auth/login
    v
Spring Boot Backend
    |
    | Validate username/password
    v
PostgreSQL
    |
    | User + Roles
    v
Pre-Authentication Token
    |
    | Role Selection
    v
POST /api/auth/authorize-role
    |
    v
Final JWT Token
    |
    v
Authenticated Application
```

Passwords are validated using Spring Security BCrypt.

The database password field must contain a BCrypt hash, for example:

```text
$2b$10$...
```

Do not store plain-text passwords.

## Flyway

Flyway handles database migrations.

Migration files are normally located at:

```text
rcf/imas-backend/src/main/resources/db/migration/
```

The application uses the PostgreSQL schema:

```text
pp
```

## Common Problems

### Maven not recognized

If you see:

```text
'mvn' is not recognized as an internal or external command
```

install Maven and add its `bin` directory to PATH.

If Maven Wrapper exists:

```cmd
mvnw.cmd spring-boot:run
```

### PostgreSQL `psql` not recognized

On Windows, PostgreSQL 17 may be run directly with:

```cmd
"C:\Program Files\PostgreSQL\17\bin\psql.exe" -U postgres -h localhost -p 5432
```

Or add the PostgreSQL `bin` directory to PATH.

### Port 8080 already in use

```cmd
netstat -ano | findstr :8080
```

Then, if appropriate:

```cmd
taskkill /PID <PID> /F
```

### Port 3000 already in use

```cmd
netstat -ano | findstr :3000
```

### CORS error

Verify:

1. Backend is running on port 8080.
2. Frontend is running on port 3000.
3. Backend CORS allows `http://localhost:3000`.
4. Frontend API requests use `http://localhost:8080`.

### 401 Unauthorized during login

Check:

- Username
- Password
- PostgreSQL connection
- `DB_NAME`
- `DB_USER`
- `DB_PASSWORD`
- User record in `pp.user`
- Mapping in `pp.user_role`
- Active role in `pp.role`
- BCrypt password hash

A BCrypt hash normally starts with `$2a$` or `$2b$`.

Do not store plain text such as `admin` in the BCrypt password column.

## Build Frontend

```bash
cd client
npm run build
```

Production files are generated in:

```text
client/build/
```

## Build Backend

```bash
cd rcf/imas-backend
mvn clean package
```

The JAR is generated under:

```text
target/
```

Run it with:

```bash
java -jar target/<generated-jar-name>.jar
```

## Git Workflow

Create a feature branch:

```bash
git checkout -b feature/your-feature-name
```

Stage:

```bash
git add .
```

Commit:

```bash
git commit -m "Add your feature description"
```

Push:

```bash
git push -u origin feature/your-feature-name
```

Then create a Pull Request on GitHub.

## Security Notes

Never commit:

- Database passwords
- JWT secrets
- API keys
- `.env` files
- Private keys
- Production credentials

Use environment variables for sensitive configuration.

## Repository

GitHub: https://github.com/vishalvaddar/pp_portal_v2

## Authors

Pratibha Poshak Portal Development Team
