INSERT INTO chat_questions (question, answer, keywords, created_at)
SELECT
 'How to create RITM?',
 'Steps to create RITM: 1. Open the service portal. 2. Choose the required catalog item. 3. Fill in the request details. 4. Review the information. 5. Click Submit to create the RITM.',
 'ritm, create ritm, request item, service request',
 CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM chat_questions WHERE question = 'How to create RITM?');

INSERT INTO chat_questions (question, answer, keywords, created_at)
SELECT
 'How to reset password?',
 'Steps to reset password: 1. Open the login page. 2. Click Forgot Password. 3. Enter your registered email or user ID. 4. Verify the OTP or reset link. 5. Create a new password and sign in again.',
 'password reset, forgot password, login issue, reset password',
 CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM chat_questions WHERE question = 'How to reset password?');
