package com.example.internalchatbot.ai.crawling;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;

@Service
public class AuthenticatedUrlReaderService {

    private final boolean loginEnabled;
    private final String loginUrl;
    private final String username;
    private final String password;
    private final String usernameSelector;
    private final String passwordSelector;
    private final String submitSelector;

    public AuthenticatedUrlReaderService(
            @Value("${secure-url.login-enabled:false}") boolean loginEnabled,
            @Value("${secure-url.login-url:}") String loginUrl,
            @Value("${secure-url.username:}") String username,
            @Value("${secure-url.password:}") String password,
            @Value("${secure-url.username-selector}") String usernameSelector,
            @Value("${secure-url.password-selector}") String passwordSelector,
            @Value("${secure-url.submit-selector}") String submitSelector
    ) {
        this.loginEnabled = loginEnabled;
        this.loginUrl = loginUrl;
        this.username = username;
        this.password = password;
        this.usernameSelector = usernameSelector;
        this.passwordSelector = passwordSelector;
        this.submitSelector = submitSelector;
    }

    public String readAfterLogin(URI targetUri) {
        if (!loginEnabled) {
            throw new IllegalStateException("Login-required URL support is disabled");
        }
        if (loginUrl.isBlank() || username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Login URL, username, and password must be configured on the backend");
        }

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--disable-gpu", "--no-sandbox");

        WebDriver driver = new ChromeDriver(options);
        try {
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            driver.get(loginUrl);
            driver.findElement(By.cssSelector(usernameSelector)).sendKeys(username);
            driver.findElement(By.cssSelector(passwordSelector)).sendKeys(password);
            driver.findElement(By.cssSelector(submitSelector)).click();
            driver.get(targetUri.toString());
            return driver.findElement(By.tagName("body")).getText();
        } finally {
            driver.quit();
        }
    }
}
