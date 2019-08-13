package com.edtech.plugtify.service;

import com.edtech.plugtify.domain.User;
import com.edtech.plugtify.repository.UserRepository;
import com.edtech.plugtify.service.dto.UserDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * component to manage users
 */

@Service
@Transactional
public class UserService {

    private Logger logger = LoggerFactory.getLogger(UserService.class);

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private CacheManager cacheManager;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            CacheManager cacheManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.cacheManager = cacheManager;
    }

    /**
     *
     * @param userDTO user from front-end
     * @param password password from front-end
     * @return User
     *
     * Method to register a user
     */
    public User registerUser(UserDTO userDTO, String password) {

        this.userRepository.findOneByLogin(userDTO.getLogin().toLowerCase().trim()).ifPresent(user -> {
            // throw username or login already used exception
        });

        this.userRepository.findOneByEmail(userDTO.getEmail().toLowerCase().trim()).ifPresent(user -> {
            // throw user email already used excepcion
        });

        // create new User
        User newUser = new User();
        newUser.setLogin(userDTO.getLogin().toLowerCase().trim());
        newUser.setEmail(userDTO.getEmail().toLowerCase().trim());
        newUser.setPassword(this.passwordEncoder.encode(password));

        this.userRepository.save(newUser);
        this.clearUserCaches(newUser);

        this.logger.debug("New User created: {}", newUser);

        return newUser;

    }

    // Clear cache by the given cache names
    public void clearUserCaches(User user) {
        Objects.requireNonNull(this.cacheManager.getCache(this.userRepository.USER_BY_LOGIN_CACHE)).evict(user.getLogin());
        Objects.requireNonNull(this.cacheManager.getCache(this.userRepository.USER_BY_EMAIL_CACHE)).evict(user.getEmail());
    }

}