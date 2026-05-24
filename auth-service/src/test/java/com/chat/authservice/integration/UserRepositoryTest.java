package com.chat.authservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.chat.authservice.models.User;
import com.chat.authservice.repositories.UserRepository;

/**
 * Repository-layer integration tests using H2 (in-memory, PostgreSQL mode)
 * and Flyway migrations from src/test/resources/db/migration.
 *
 * @DataJpaTest rolls back each test in a transaction so tests are isolated.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("UserRepository (DataJpaTest)")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    private User buildUser(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword("$2a$12$hashed");
        return user;
    }

    @BeforeEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    // ------------------------------------------------------------------ //
    //  save / findById                                                      //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("persists a new user and auto-generates UUID")
        void persistsUserWithAutoId() {
            User saved = userRepository.save(buildUser("alice", "alice@example.com"));

            assertThat(saved.getId()).isNotNull();
            assertThat(userRepository.findById(saved.getId())).isPresent();
        }

        @Test
        @DisplayName("sets createdAt via @PrePersist")
        void setsCreatedAt() {
            User saved = userRepository.save(buildUser("bob", "bob@example.com"));
            assertThat(saved.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws on duplicate username (unique constraint)")
        void throwsOnDuplicateUsername() {
            userRepository.save(buildUser("charlie", "charlie@example.com"));

            User duplicate = buildUser("charlie", "other@example.com");
            assertThatThrownBy(() -> {
                userRepository.save(duplicate);
                userRepository.flush();
            }).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("throws on duplicate email (unique constraint)")
        void throwsOnDuplicateEmail() {
            userRepository.save(buildUser("diana", "diana@example.com"));

            User duplicate = buildUser("diana2", "diana@example.com");
            assertThatThrownBy(() -> {
                userRepository.save(duplicate);
                userRepository.flush();
            }).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ------------------------------------------------------------------ //
    //  findByUsername                                                       //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("findByUsername()")
    class FindByUsername {

        @Test
        @DisplayName("returns user when username exists")
        void returnsUserWhenFound() {
            userRepository.save(buildUser("eve", "eve@example.com"));

            Optional<User> result = userRepository.findByUsername("eve");

            assertThat(result).isPresent();
            assertThat(result.get().getUsername()).isEqualTo("eve");
        }

        @Test
        @DisplayName("returns empty Optional when username does not exist")
        void returnsEmptyWhenNotFound() {
            Optional<User> result = userRepository.findByUsername("nobody");
            assertThat(result).isEmpty();
        }
    }

    // ------------------------------------------------------------------ //
    //  existsByUsername                                                     //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("existsByUsername()")
    class ExistsByUsername {

        @Test
        @DisplayName("returns true when username exists")
        void returnsTrueWhenExists() {
            userRepository.save(buildUser("frank", "frank@example.com"));
            assertThat(userRepository.existsByUsername("frank")).isTrue();
        }

        @Test
        @DisplayName("returns false when username does not exist")
        void returnsFalseWhenAbsent() {
            assertThat(userRepository.existsByUsername("ghost")).isFalse();
        }
    }

    // ------------------------------------------------------------------ //
    //  existsByEmail                                                        //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("existsByEmail()")
    class ExistsByEmail {

        @Test
        @DisplayName("returns true when email exists")
        void returnsTrueWhenExists() {
            userRepository.save(buildUser("grace", "grace@example.com"));
            assertThat(userRepository.existsByEmail("grace@example.com")).isTrue();
        }

        @Test
        @DisplayName("returns false when email does not exist")
        void returnsFalseWhenAbsent() {
            assertThat(userRepository.existsByEmail("nobody@example.com")).isFalse();
        }
    }

    // ------------------------------------------------------------------ //
    //  findById                                                             //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("returns user for a valid UUID")
        void returnsUserForValidId() {
            User saved = userRepository.save(buildUser("henry", "henry@example.com"));

            Optional<User> result = userRepository.findById(saved.getId());
            assertThat(result).isPresent();
            assertThat(result.get().getEmail()).isEqualTo("henry@example.com");
        }

        @Test
        @DisplayName("returns empty Optional for a non-existent UUID")
        void returnsEmptyForUnknownId() {
            Optional<User> result = userRepository.findById(UUID.randomUUID());
            assertThat(result).isEmpty();
        }
    }
}
