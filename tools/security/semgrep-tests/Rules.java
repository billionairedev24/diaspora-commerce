// Semgrep rule fixtures for tools/security/semgrep.yml (run: semgrep --test --config ../semgrep.yml .).
package fixtures;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class Rules {
    // ruleid: at.java.no-field-injection
    @Autowired
    private JdbcTemplate template;

    private final JdbcClient jdbc;
    private final EntityManager em;

    // ok: at.java.no-field-injection
    @Autowired
    Rules(JdbcClient jdbc, EntityManager em) {
        this.jdbc = jdbc;
        this.em = em;
    }

    void sql(String sellerId, String sort) {
        // ruleid: at.java.sql-injection
        jdbc.sql("SELECT * FROM offers WHERE seller_id = '" + sellerId + "'").query().listOfRows();
        // ruleid: at.java.sql-injection
        template.queryForList(String.format("select id from offers order by %s", sort));
        // ruleid: at.java.sql-injection
        em.createNativeQuery("delete from offers where id = " + sellerId);
        // ok: at.java.sql-string-concatenation
        jdbc.sql("SELECT * FROM offers WHERE seller_id = :id").param("id", sellerId).query().listOfRows();
        // ok: at.java.sql-string-concatenation
        jdbc.sql("SELECT * FROM offers " + "WHERE seller_id = :id").param("id", sellerId).query().listOfRows();
        StringBuilder where = new StringBuilder();
        if (!sellerId.isEmpty()) {
            where.append(" where seller_id = :id");
        }
        // ruleid: at.java.sql-string-concatenation
        jdbc.sql("select count(*) from offers" + where).param("id", sellerId).query(Long.class).single();
        // ok: at.java.sql-injection
        jdbc.sql("select count(*) from offers" + where).param("id", sellerId).query(Long.class).single();
        // ok: at.java.sql-string-concatenation
        jdbc.sql("select " + COLUMNS + " from offers where id = :id").param("id", sellerId).query().listOfRows();
    }

    private static final String COLUMNS = "id, seller_id, price_minor";

    void log(Exception e) {
        // ruleid: at.java.no-system-out
        System.out.println("debug");
        // ruleid: at.java.no-system-out
        e.printStackTrace();
    }
}
