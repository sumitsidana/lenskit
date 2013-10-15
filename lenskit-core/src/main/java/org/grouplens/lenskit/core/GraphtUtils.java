/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2013 Regents of the University of Minnesota and contributors
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.grouplens.lenskit.core;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.grouplens.grapht.graph.Edge;
import org.grouplens.grapht.graph.Graph;
import org.grouplens.grapht.graph.Node;
import org.grouplens.grapht.spi.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Helper utilities for Grapht integration.
 *
 * @author <a href="http://www.grouplens.org">GroupLens Research</a>
 * @since 0.11
 */
public final class GraphtUtils {
    private GraphtUtils() {
    }

    public static Node replaceNodeWithPlaceholder(InjectSPI spi, Graph graph, Node node) {
        // replace it with a null satisfaction
        final CachedSatisfaction daoLbl = node.getLabel();
        assert daoLbl != null;
        final Satisfaction oldSat = daoLbl.getSatisfaction();
        final Class<?> type = oldSat.getErasedType();
        final Satisfaction sat = spi.satisfyWithNull(type);
        final Node placeholder = new Node(sat, CachePolicy.MEMOIZE);
        graph.replaceNode(node, placeholder);

        // replace desires on edges (truncates desire chains to only contain head, dropping refs)
        for (Edge e: Lists.newArrayList(graph.getIncomingEdges(placeholder))) {
            Desire d = e.getDesire();
            List<Desire> lbl = null;
            if (d != null) {
                lbl = Collections.singletonList(d);
            }
            Edge replacement = new Edge(e.getHead(), e.getTail(), lbl);
            graph.replaceEdge(e, replacement);
        }

        return placeholder;
    }

    /**
     * Determine if a node is a shareable component.
     *
     * @param node The node.
     * @return {@code true} if the component is shareable.
     */
    public static boolean isShareable(Node node) {
        CachedSatisfaction label = node.getLabel();
        if (label == null) {
            return false;
        }

        if (label.getSatisfaction().hasInstance()) {
            return true;
        }

        if (label.getCachePolicy() == CachePolicy.NEW_INSTANCE) {
            return false;
        }

        Class<?> type = label.getSatisfaction().getErasedType();
        if (type.getAnnotation(Shareable.class) != null) {
            return true;
        }

        if (type.getAnnotation(Singleton.class) != null) {
            return true;
        }

        // finally examine the satisfaction in more detail
        return label.getSatisfaction().visit(new AbstractSatisfactionVisitor<Boolean>() {
            @Override
            public Boolean visitDefault() {
                return false;
            }

            @Override
            public Boolean visitProviderClass(Class<? extends Provider<?>> pclass) {
                Method m = null;
                try {
                    m = pclass.getMethod("get");
                } catch (NoSuchMethodException e) {
                /* fine, leave it null */
                }
                if (m != null && m.getAnnotation(Shareable.class) != null) {
                    return true;
                }
                return false;
            }

            @SuppressWarnings("unchecked")
            @Override
            public Boolean visitProviderInstance(Provider<?> provider) {
                // cast to raw type to work around inference issue
                return visitProviderClass((Class) provider.getClass());
            }
        });
    }

    /**
     * Remove transient edges from a set.
     *
     * @param edges The set of edges.
     * @return A new set containing only the non-transient edges.
     */
    public static Set<Edge> removeTransient(Set<Edge> edges) {
        return Sets.filter(edges, new Predicate<Edge>() {
            @Override
            public boolean apply(@Nullable Edge input) {
                Desire desire = input == null ? null : input.getDesire();
                return desire == null || !desireIsTransient(desire);
            }
        });
    }

    /**
     * Determine whether a desire is transient.
     *
     * @param d The desire to test.
     * @return {@code true} if the desire is transient.
     */
    public static boolean desireIsTransient(@Nonnull Desire d) {
        Attributes attrs = d.getInjectionPoint().getAttributes();
        return attrs.getAttribute(Transient.class) != null;
    }

    /**
     * Predicate satisfied by nodes whose satisfactions have the specified type.
     *
     * @param type The requested type.
     * @return A predicate accepting nodes with the specified type.
     */
    public static Predicate<Node> nodeHasType(final Class<?> type) {
        return new Predicate<Node>() {
            @Override
            public boolean apply(@Nullable Node input) {
                CachedSatisfaction lbl = input == null ? null : input.getLabel();
                return lbl != null && type.isAssignableFrom(lbl.getSatisfaction().getErasedType());
            }
        };
    }

    /**
     * Function to extract the tail of a node.
     *
     * @return A function extracting the tail of a node.
     */
    public static Function<Edge, Node> edgeTail() {
        return EdgeTail.INSTANCE;
    }

    private static class EdgeTail implements Function<Edge, Node> {
        public static final EdgeTail INSTANCE = new EdgeTail();

        @Override
        public Node apply(@Nullable Edge input) {
            return input == null ? null : input.getTail();
        }
    }
}