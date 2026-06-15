package com.ems.backend.user;

import com.ems.backend.common.PageResponse;
import com.ems.backend.user.dto.UpdateUserRequest;
import com.ems.backend.user.dto.UserResponse;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(this::map)
                .toList();
    }

    public PageResponse<UserResponse> getUsersPaged(int page, int size, String search) {
        String query = search != null ? search.trim().toLowerCase() : "";
        List<UserResponse> filtered = getAllUsers().stream()
                .filter(user -> query.isEmpty()
                        || user.fullName().toLowerCase().contains(query)
                        || user.email().toLowerCase().contains(query)
                        || user.role().name().toLowerCase().contains(query))
                .toList();
        return PageResponse.of(filtered, page, size);
    }

    private UserResponse map(User user) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getActive(),
                user.getJobTitle(),
                user.getProfilePhotoUrl()
        );
    }

    public UserResponse updateUser(Long id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Only check email uniqueness if it's changing
        if (!user.getEmail().equals(request.email()) && userRepository.existsByEmail(request.email())) {
            throw new RuntimeException("Email already in use");
        }

        user.setFullName(request.fullName());
        user.setEmail(request.email());
        user.setRole(request.role());
        user.setActive(request.active());
        user.setJobTitle(request.jobTitle() == null || request.jobTitle().isBlank() ? null : request.jobTitle().trim());

        User updatedUser = userRepository.save(user);

        return new UserResponse(
                updatedUser.getId(),
                updatedUser.getFullName(),
                updatedUser.getEmail(),
                updatedUser.getRole(),
                updatedUser.getActive(),
                updatedUser.getJobTitle(),
                updatedUser.getProfilePhotoUrl()
        );
    }

    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        // Soft delete: deactivate the user
        user.setActive(false);
        userRepository.save(user);
    }
}
