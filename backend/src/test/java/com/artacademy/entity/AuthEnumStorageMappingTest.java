package com.artacademy.entity;

import com.artacademy.enums.AgeGroup;
import com.artacademy.enums.ProfileType;
import com.artacademy.enums.Role;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AuthEnumStorageMappingTest {
    @Test
    void authEnumsUseVarcharWithoutChangingEnumValues() throws Exception {
        assertVarchar(User.class.getDeclaredField("role"));
        assertVarchar(UserProfile.class.getDeclaredField("profileType"));
        assertVarchar(UserProfile.class.getDeclaredField("ageGroup"));

        assertArrayEquals(new Role[]{Role.USER, Role.INSTRUCTOR, Role.STAFF, Role.ADMIN}, Role.values());
        assertArrayEquals(new ProfileType[]{ProfileType.LEARNER, ProfileType.INSTRUCTOR}, ProfileType.values());
        assertArrayEquals(new AgeGroup[]{AgeGroup.KIDS, AgeGroup.TEEN, AgeGroup.ADULT, AgeGroup.SENIOR}, AgeGroup.values());
    }

    private static void assertVarchar(Field field) {
        JdbcTypeCode mapping = field.getAnnotation(JdbcTypeCode.class);
        assertNotNull(mapping, field.getName() + " must explicitly use a VARCHAR JDBC type");
        assertEquals(SqlTypes.VARCHAR, mapping.value());
    }
}
