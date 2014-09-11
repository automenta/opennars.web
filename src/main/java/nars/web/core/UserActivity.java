/*
 * Copyright (C) 2014 me
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package nars.web.core;

import com.thinkaurelius.titan.core.TitanTransaction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Vertex;
import java.util.List;
import java.util.Map;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.impl.Json;

/**
 *
 * @author me
 */
public class UserActivity implements Handler<Message> {
    private final Core core;
    private final EventBus bus;

    public UserActivity(Core c, EventBus b) {
        
        this.core = c;
        this.bus = b;
        b.registerHandler("publish", this);
    }

    
    @Override
    public void handle(Message e) {
        switch (e.address()) {
            case "publish":
                try {
                    publish(e.body().toString());
                }
                catch (Exception ex) {
                    System.err.println("Unable to decode: " + ex);
                    ex.printStackTrace();
                }
                break;
        }
        
    }
    
    public void publish(String nobjectJSON) {
        Map m = Json.decodeValue(nobjectJSON, Map.class);
        Object pred;
        if ((pred = m.get("predicate"))!=null) {
            
            long createdAt = (long) m.get("createdAt");
            String author = (String) m.get("author");
            String subj = (String)m.get("subject");
            String obj = (String)m.get("object");
            if ((subj!=null) && (obj!=null)) {
                if (pred instanceof List) {
                    List<String> l = (List)pred;
                    TitanTransaction t = core.graph.newTransaction();
                    
                    Vertex sv = core.vertex(t, subj, true);
                    sv.setProperty("type", "user");
                    
                    Vertex ov = core.vertex(t, obj, true);                    
                    
                    for (String p : l) {                        
                        Edge e = core.addEdge(t, sv, ov, p);
                        e.setProperty("author", author);
                        e.setProperty("createdAt", createdAt);
                    }
                    
                    t.commit();

                }
            }
        }
        
    }
}