package dev.felicityiris.llmsysmon.database;

import dev.felicityiris.llmsysmon.metrics.SystemSnapshot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

import java.time.Instant;
import java.util.List;

public class MetricRepository {
    private final EntityManagerFactory entityManagerFactory;

    public MetricRepository(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    public void save(SystemSnapshot snapshot) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();

        try {
            entityManager.getTransaction().begin();
            entityManager.persist(MetricEntity.from(snapshot));
            entityManager.getTransaction().commit();
        } catch (RuntimeException e) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }

            throw e;
        } finally {
            entityManager.close();
        }
    }

    public List<SystemSnapshot> findRecent(int limit) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();

        try {
            TypedQuery<MetricEntity> query = entityManager.createQuery(
                    "FROM MetricEntity ORDER BY timestamp DESC",
                    MetricEntity.class
            );

            query.setMaxResults(limit);

            return query.getResultList().stream().map(MetricEntity::toSnapshot).toList();
        } finally {
            entityManager.close();
        }
    }

    public List<SystemSnapshot> findBetween(Instant start, Instant end) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();

        try {
            TypedQuery<MetricEntity> query = entityManager.createQuery(
                    "FROM MetricEntity WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp",
                    MetricEntity.class
            );

            query.setParameter("start", start.toEpochMilli());
            query.setParameter("end", end.toEpochMilli());

            return query.getResultList().stream().map(MetricEntity::toSnapshot).toList();
        } finally {
            entityManager.close();
        }
    }
}
