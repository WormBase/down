FROM phusion/baseimage:0.9.19
RUN apt-get update && \
    apt-get install -y nginx && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*
RUN rm -f /etc/nginx/sites-enabled/default

# forward request and error logs to docker log collector
run ln -sf /dev/stdout /var/log/nginx/access.log
RUN ln -sf /dev/stderr /var/log/nginx/error.log
VOLUME ["/var/log"]
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
