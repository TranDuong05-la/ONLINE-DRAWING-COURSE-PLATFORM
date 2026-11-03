package com.artacademy.learning;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.artacademy.entity.User;
import com.artacademy.entity.UserProfile;
import com.artacademy.enums.ProfileType;
import com.artacademy.repository.UserProfileRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * Tìm (hoặc tạo lần đầu) hồ sơ LEARNER của một user. Dữ liệu học tập gắn với hồ sơ học viên, nhưng các phần khác
 * (D, controller) chỉ truyền userId; C tự đổi userId → learnerProfileId ở đây.
 *
 * <p>Bảng user_profiles chưa có UNIQUE(user_id, profile_type), nên việc tạo hồ sơ được tuần tự hóa bằng cách khóa
 * dòng users (SELECT ... FOR UPDATE) trước khi kiểm tra rồi mới tạo: hai request đồng thời không tạo ra 2 hồ sơ.
 */
@Service
public class LearnerProfileService {
	private final UserProfileRepository profiles;
	private final EntityManager em;
	private final TransactionTemplate requiresNew;

	public LearnerProfileService(UserProfileRepository profiles, EntityManager em, PlatformTransactionManager tx) {
		this.profiles = profiles;
		this.em = em;
		this.requiresNew = new TransactionTemplate(tx);
		this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** Chỉ đọc, không tạo. Null nếu user chưa có hồ sơ học viên (chưa học/mua gì). */
	@Transactional(readOnly = true)
	public Integer findLearnerProfileId(Integer userId) {
		return profiles.findByUserIdAndProfileType(userId, ProfileType.LEARNER).map(UserProfile::getId).orElse(null);
	}

	/** Hồ sơ học viên của user, tạo mới nếu chưa có. */
	public Integer learnerProfileId(Integer userId) {
		if (userId == null || userId <= 0) {
			throw new IllegalArgumentException("userId must be positive");
		}
		Integer existing = findLearnerProfileId(userId);
		if (existing != null) {
			return existing;
		}
		// Tạo trong transaction riêng để hồ sơ tồn tại ngay cả khi transaction gọi tới bị rollback sau đó.
		return requiresNew.execute(status -> {
			User user = em.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE);
			if (user == null) {
				throw Errors.notFound("Không tìm thấy người dùng");
			}
			return profiles.findByUserIdAndProfileType(userId, ProfileType.LEARNER).map(UserProfile::getId)
					.orElseGet(() -> profiles.save(UserProfile.builder()
							.user(user)
							.profileType(ProfileType.LEARNER)
							.displayName(user.getFullName())
							.isApproved(true)
							.build()).getId());
		});
	}

	/** userId sở hữu hồ sơ học viên (để gửi thông báo). */
	@Transactional(readOnly = true)
	public Integer userIdOf(Integer learnerProfileId) {
		return profiles.findById(learnerProfileId).map(p -> p.getUser().getId())
				.orElseThrow(() -> Errors.notFound("Không tìm thấy hồ sơ học viên"));
	}

	/** Email của chủ hồ sơ học viên (null nếu không có). */
	@Transactional(readOnly = true)
	public String emailOf(Integer learnerProfileId) {
		return profiles.findById(learnerProfileId).map(p -> p.getUser().getEmail()).orElse(null);
	}

	/** Tên hiển thị trên chứng nhận. */
	@Transactional(readOnly = true)
	public String displayName(Integer learnerProfileId) {
		return profiles.findById(learnerProfileId).map(UserProfile::getDisplayName).orElse("Học viên");
	}
}
