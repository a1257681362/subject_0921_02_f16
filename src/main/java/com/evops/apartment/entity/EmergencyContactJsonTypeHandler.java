package com.evops.apartment.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 紧急联系人嵌套对象 ↔ t_lease.emergency_contact（JSON 列）的类型处理器（apt-12）。
 *
 * <p>库内保持明文 JSON（与 apt-2 口径一致：脱敏只发生在出口，落库不脱敏）。
 * 故存储专用 ObjectMapper 关闭 USE_ANNOTATIONS——否则字段上 @Sensitive 自带的
 * Jackson 序列化器会把姓名/电话打码后再落库，破坏“库内明文”口径。
 */
public class EmergencyContactJsonTypeHandler extends BaseTypeHandler<EmergencyContact> {
    private static final ObjectMapper STORAGE_MAPPER = new ObjectMapper()
            .disable(MapperFeature.USE_ANNOTATIONS);

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, EmergencyContact parameter,
                                    JdbcType jdbcType) throws SQLException {
        ps.setString(i, write(parameter));
    }

    @Override
    public EmergencyContact getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return read(rs.getString(columnName));
    }

    @Override
    public EmergencyContact getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return read(rs.getString(columnIndex));
    }

    @Override
    public EmergencyContact getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return read(cs.getString(columnIndex));
    }

    private static String write(EmergencyContact value) {
        try {
            return STORAGE_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("紧急联系人序列化失败", e);
        }
    }

    private static EmergencyContact read(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return STORAGE_MAPPER.readValue(json, EmergencyContact.class);
        } catch (Exception e) {
            throw new IllegalStateException("紧急联系人解析失败: " + json, e);
        }
    }
}
