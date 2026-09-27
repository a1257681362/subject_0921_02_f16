package com.evops.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.rbac.entity.RbacUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RbacUserMapper extends BaseMapper<RbacUser> {
}
