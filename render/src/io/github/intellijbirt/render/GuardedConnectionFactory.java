package io.github.intellijbirt.render;

import org.eclipse.birt.report.data.oda.jdbc.IConnectionFactory;
import org.eclipse.birt.report.data.oda.jdbc.JDBCDriverManager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * 설계 화면의 DB 접속(데이터셋 Preview Results·열 가져오기·데이터 소스 Test Connection) — 미리보기와 같은 DB 보호:
 * 접속 주소에 차단 값이 들어 있거나, 허용 목록이 있는데 어느 값도 안 들어 있으면 접속하지 않음 (IntelliJ 설정·.intellij-birt.properties).
 * 통과하면 BIRT가 하던 대로 JDBC 번들의 drivers 폴더에서 드라이버를 불러 접속.
 * BIRT JDBC 번들의 driverinfo 확장으로 드라이버 클래스마다 등록 (plugin.xml).
 */
public final class GuardedConnectionFactory implements IConnectionFactory {

    private static volatile List<String> allow = Collections.emptyList();
    private static volatile List<String> deny = Collections.emptyList();

    /** IntelliJ가 리포트를 열 때 보냄 */
    static void setHosts(List<String> allowHosts, List<String> denyHosts) {
        allow = allowHosts;
        deny = denyHosts;
    }

    @Override
    public Connection getConnection(String driverClass, String url, Properties connectionProperties) throws SQLException {
        // SQLState 28000(인증 거부)으로 던져야 함 — BIRT는 연결 공장이 다른 오류를 던지면 무시하고 원래 방식으로 다시 접속함
        String target = url == null ? "" : url;
        for (String d : deny) {
            if (target.contains(d)) {
                throw new SQLException("이 DB는 접속하지 않도록 막혀 있습니다 (미리보기 차단 DB: " + d + ")", "28000");
            }
        }
        if (!allow.isEmpty() && allow.stream().noneMatch(target::contains)) {
            throw new SQLException("미리보기 허용 DB가 아니라 접속하지 않습니다 (허용: " + String.join(", ", allow) + ")", "28000");
        }
        try {
            JDBCDriverManager.getInstance().loadAndRegisterDriver(driverClass, null);
        } catch (Exception e) {
            throw new SQLException("JDBC 드라이버를 찾지 못했습니다: " + driverClass, e);
        }
        return DriverManager.getConnection(url, connectionProperties);
    }
}
