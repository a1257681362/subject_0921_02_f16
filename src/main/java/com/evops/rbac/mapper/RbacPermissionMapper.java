package com.evops.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.rbac.entity.RbacPermission;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RbacPermissionMapper extends BaseMapper<RbacPermission> {
}
