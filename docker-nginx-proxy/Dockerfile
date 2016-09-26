FROM nginx:alpine
RUN rm -f /etc/nginx/sites-enabled/default

# forward request and error logs to docker log collector
RUN ln -sf /dev/stdout /var/log/nginx/access.log && \
    ln -sf /dev/stderr /var/log/nginx/error.log

VOLUME ["/var/log"]

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
