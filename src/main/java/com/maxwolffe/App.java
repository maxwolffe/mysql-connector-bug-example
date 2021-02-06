package com.maxwolffe;

import java.util.HashMap;
import java.util.List;
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
        properties.put("eclipselink.jdbc.property.cachePrepStmts", "false");
        properties.put("eclipselink.jdbc.property.useServerPrepStmts", "true");
        
        EntityManagerFactory emf = Persistence.createEntityManagerFactory(
                "cars-pu",
                properties
        );
        EntityManager eman = emf.createEntityManager();

        try {

            String sql = "SELECT c FROM Car c";
            String nativeSql = "SELECT * FROM testdb.Cars";
            
            Query query = eman.createQuery(sql);
            List<Car> cars = query.getResultList();

            // This first statement and resultSet is of type FORWARD_ONLY
            Query nativeQuery = eman.createNativeQuery(nativeSql);
            nativeQuery.getResultList();
            
            // EJBQ queries have the same issue. 
            //Query query2 = eman.createQuery(sql);
            //query2.setFirstResult(1);
            //query2.getResultList(); 
            
            // The second statement should be of type "INSENTITIVE_SCROLL" because of the "setFirstResult" call. But it's cached as FORWARD_ONLY.
            Query nativeQuery2 = eman.createNativeQuery(nativeSql);
            nativeQuery2.setFirstResult(2);
            nativeQuery2.getResultList();
            
            System.out.println();
            System.out.println("Queries successful!");

        } finally {

            eman.close();
            emf.close();
        }
    }
}
