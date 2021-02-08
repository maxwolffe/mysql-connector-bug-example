package com.maxwolffe;

import java.util.HashMap;
import java.util.Map;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.persistence.Query;

public class App 
{
    public static void main(String[] args) {
        
        Map<String, String> properties = new HashMap<>();
        // When this property is set to "true" - statements are cached, even if the resultSetType should be changing.
        // Change this value to "false" to get the queries executing correctly.
        properties.put("eclipselink.jdbc.property.cachePrepStmts", "true");
        properties.put("eclipselink.jdbc.property.useServerPrepStmts", "true");
        
        EntityManagerFactory emf = Persistence.createEntityManagerFactory(
                "cars-pu",
                properties
        );
        EntityManager eman = emf.createEntityManager();

        try {
            String nativeSql = "SELECT * FROM testdb.Cars";

            // This first statement and resultSet is of type FORWARD_ONLY
            Query nativeQuery = eman.createNativeQuery(nativeSql);
            nativeQuery.getResultList();
            
            // The second statement should be of type "INSENSITIVE_SCROLL" because of the "setFirstResult" call. But it's cached as FORWARD_ONLY.
            Query nativeQuery2 = eman.createNativeQuery(nativeSql);
            nativeQuery2.setFirstResult(2);
            nativeQuery2.getResultList();
            
            System.out.println();
            System.out.println("Queries successful!");

            // EJBQ queries have the same issue.

            // String sql = "SELECT c FROM Car c";
            // Query query = eman.createQuery(sql);
            // List<Car> cars = query.getResultList();

            // Query query2 = eman.createQuery(sql);
            // query2.setFirstResult(1);
            // query2.getResultList(); 

        } finally {

            eman.close();
            emf.close();
        }
    }
}
