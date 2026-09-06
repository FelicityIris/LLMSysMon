package dev.felicityiris.llmsysmon.database;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

public class DatabaseConfig {
    private final EntityManagerFactory entityManagerFactory;

    public DatabaseConfig() {
        this.entityManagerFactory = Persistence.createEntityManagerFactory("llmsysmon");
    }

    public EntityManagerFactory getEntityManagerFactory() {
        return entityManagerFactory;
    }

    public void close() {
        entityManagerFactory.close();
    }
}
