package org.acme;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.annotation.Priority;
import javax.json.JsonStructure;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.ws.rs.Consumes;
import javax.ws.rs.Priorities;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.ext.Providers;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import io.quarkus.runtime.annotations.RegisterForReflection;

@Provider
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Priority(Priorities.USER - 200)
@RegisterForReflection
public class JsonRoutingProvider<T> implements MessageBodyReader<T>, MessageBodyWriter<T> {
    private volatile Jackson jackson;
    private volatile JsonbJaxrsProvider jsonb;

    private final ConcurrentMap<Type, JsonProvider> router = new ConcurrentHashMap<>();

    @Context
    private Providers providers;

    @Override
    public boolean isReadable(final Class<?> type, final Type genericType,
                              final Annotation[] annotations, final MediaType mediaType) {
        init();
        return jsonb.isReadable(type, genericType, annotations, mediaType) ||
                jackson.isReadable(type, genericType, annotations, mediaType);
    }

    @Override
    public T readFrom(final Class<T> type, final Type genericType, final Annotation[] annotations,
                      final MediaType mediaType, final MultivaluedMap<String, String> httpHeaders,
                      final InputStream entityStream) throws IOException, WebApplicationException {
        init();
        final JsonProvider provider = router.computeIfAbsent(genericType, t -> isJackson(t, new HashSet<>()) ? jackson : jsonb);
        return (T) provider.readFrom(Class.class.cast(type), genericType, annotations, mediaType, httpHeaders, entityStream);
    }

    @Override
    public boolean isWriteable(final Class<?> type, final Type genericType, final Annotation[] annotations,
                               final MediaType mediaType) {
        init();
        return jsonb.isWriteable(type, genericType, annotations, mediaType) ||
                jackson.isWriteable(type, genericType, annotations, mediaType);
    }

    @Override
    public long getSize(final T t, final Class<?> type, final Type genericType, final Annotation[] annotations,
                        final MediaType mediaType) {
        return -1;
    }

    @Override
    public void writeTo(final T t, final Class<?> type, final Type genericType,
                        final Annotation[] annotations, final MediaType mediaType,
                        final MultivaluedMap<String, Object> httpHeaders, final OutputStream entityStream) throws IOException, WebApplicationException {
        init();
        final JsonProvider provider = router.computeIfAbsent(genericType, key -> isJackson(key, new HashSet<>()) ? jackson : jsonb);
        provider.writeTo(t, type, genericType, annotations, mediaType, httpHeaders, entityStream);
    }

    private void init() {
        if (jsonb != null) {
            return;
        }
        synchronized (this) {
            if (jsonb == null) {
                jackson = new Jackson(providers);
                jsonb = new JsonbJaxrsProvider(providers);
            }
        }
    }

    private boolean isJackson(final Type genericType, final Set<Type> visited) {
        if (!visited.add(genericType)) {
            return false;
        }
        if (Class.class.isInstance(genericType)) {
            final Class<?> clazz = Class.class.cast(genericType);
            if (clazz.isArray() && clazz != clazz.getComponentType()) {
                return isJackson(clazz.getComponentType(), visited);
            }
            return hasJacksonAnnotations(clazz);
        }
        if (ParameterizedType.class.isInstance(genericType)) {
            final ParameterizedType pt = ParameterizedType.class.cast(genericType);
            if (pt.getRawType() == Map.class) {
                return pt.getActualTypeArguments().length == 2 && isJackson(pt.getActualTypeArguments()[1], visited);
            }
            if (Class.class.isInstance(pt.getRawType()) && Collection.class.isAssignableFrom(Class.class.cast(pt.getRawType()))) {
                return pt.getActualTypeArguments().length == 1 && isJackson(pt.getActualTypeArguments()[0], visited);
            }
            return false;
        }
        return false;
    }

    private boolean hasJacksonAnnotations(final Class<?> clazz) {
        return Stream.of(clazz)
                .flatMap(c -> Stream.concat(Stream.concat(Stream.concat(
                        Stream.of(clazz.getAnnotations()),
                        Stream.of(clazz.getDeclaredFields())
                                .flatMap(f -> Stream.of(f.getAnnotations()))),
                        Stream.of(clazz.getDeclaredMethods())
                                .flatMap(f -> Stream.of(f.getAnnotations()))),
                        Stream.of(clazz.getDeclaredConstructors())
                                .flatMap(f -> Stream.of(f.getAnnotations()))))
                .map(Annotation::annotationType)
                .distinct()
                .filter(a -> a.getName().startsWith("com.fasterxml.jackson."))
                .findFirst()
                .map(whateverMatches -> true)
                .orElseGet(() ->
                        clazz.getSuperclass() != null &&
                                clazz.getSuperclass() != Object.class &&
                                hasJacksonAnnotations(clazz.getSuperclass()));
    }

    // just to share a common cacheable routed api and avoid 2 router maps
    private interface JsonProvider extends MessageBodyWriter<Object>, MessageBodyReader<Object> {
    }

    private static class Jackson extends JacksonJsonProvider implements JsonProvider {
        private Jackson(final Providers providers) {
            this._providers = providers;
        }
    }

    // simplified from johnzon
    private static class JsonbJaxrsProvider implements JsonProvider, AutoCloseable {

        private volatile Function<Class<?>, Jsonb> delegate = null;

        private final Providers providers;

        private JsonbJaxrsProvider(final Providers providers) {
            this.providers = providers;
        }

        @Override
        public boolean isReadable(final Class<?> type, final Type genericType, final Annotation[] annotations, final MediaType mediaType) {
            return !InputStream.class.isAssignableFrom(type)
                    && !Reader.class.isAssignableFrom(type)
                    && !Response.class.isAssignableFrom(type)
                    && !CharSequence.class.isAssignableFrom(type)
                    && !JsonStructure.class.isAssignableFrom(type);
        }

        @Override
        public boolean isWriteable(final Class<?> type, final Type genericType, final Annotation[] annotations, final MediaType mediaType) {
            return !InputStream.class.isAssignableFrom(type)
                    && !OutputStream.class.isAssignableFrom(type)
                    && !Writer.class.isAssignableFrom(type)
                    && !StreamingOutput.class.isAssignableFrom(type)
                    && !CharSequence.class.isAssignableFrom(type)
                    && !Response.class.isAssignableFrom(type)
                    && !JsonStructure.class.isAssignableFrom(type);
        }

        @Override
        public long getSize(final Object t, final Class<?> type, final Type genericType, final Annotation[] annotations, final MediaType mediaType) {
            return -1;
        }

        @Override
        public Object readFrom(final Class<Object> type, final Type genericType, final Annotation[] annotations, final MediaType mediaType,
                               final MultivaluedMap<String, String> httpHeaders, final InputStream entityStream) throws IOException, WebApplicationException {
            return getJsonb(type).fromJson(entityStream, genericType);
        }

        @Override
        public void writeTo(final Object t, final Class<?> type, final Type genericType, final Annotation[] annotations, final MediaType mediaType,
                            final MultivaluedMap<String, Object> httpHeaders, final OutputStream entityStream) throws IOException, WebApplicationException {
            getJsonb(type).toJson(t, entityStream);
        }

        @Override
        public synchronized void close() throws Exception {
            if (AutoCloseable.class.isInstance(delegate)) {
                AutoCloseable.class.cast(delegate).close();
            }
        }

        private Jsonb getJsonb(final Class<?> type) {
            if (delegate == null) {
                synchronized (this) {
                    if (delegate == null) {
                        final ContextResolver<Jsonb> contextResolver = providers == null ?
                                null : providers.getContextResolver(Jsonb.class, MediaType.APPLICATION_JSON_TYPE);
                        if (contextResolver != null) {
                            delegate = new DynamicInstance(contextResolver); // faster than contextResolver::getContext
                        } else {
                            delegate = new ProvidedInstance(JsonbBuilder.create()); // don't recreate it
                        }
                    }
                }
            }
            return delegate.apply(type);
        }

        private static final class DynamicInstance implements Function<Class<?>, Jsonb> {
            private final ContextResolver<Jsonb> contextResolver;

            private DynamicInstance(final ContextResolver<Jsonb> resolver) {
                this.contextResolver = resolver;
            }

            @Override
            public Jsonb apply(final Class<?> type) {
                return contextResolver.getContext(type);
            }
        }

        private static final class ProvidedInstance implements Function<Class<?>, Jsonb>, AutoCloseable {
            private final Jsonb instance;

            private ProvidedInstance(final Jsonb instance) {
                this.instance = instance;
            }

            @Override
            public Jsonb apply(final Class<?> aClass) {
                return instance;
            }

            @Override
            public void close() throws Exception {
                instance.close();
            }
        }
    }
}
