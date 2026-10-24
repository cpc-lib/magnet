package cc.ivera.magnet.infrastructure.mapper;

import cc.ivera.magnet.domain.user.AppUser;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

public interface UserMapper {

    AppUser findById(@Param("id") UUID id);

    AppUser findByUsername(@Param("username") String username);

    void insert(AppUser user);

    long count();
}
