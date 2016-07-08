(ns web.anti-forgery
  "Ring middleware to prevent CSRF attacks with an anti-forgery token."
  (:use [hiccup core form])
  (:require [crypto.random :as random]
            [crypto.equality :as crypto]))

(def ^:dynamic
  ^{:doc "Binding that stores a anti-forgery token that must be included
          in POST forms if the handler is wrapped in wrap-anti-forgery."}
  *anti-forgery-token*)

(defn- session-token [request]
  (or (get-in request [:session "__anti-forgery-token"])
      (random/base64 60)))

(defn- assoc-session-token [response request token]
  (let [old-token (get-in request [:session "__anti-forgery-token"])]
    (if (= old-token token)
      response
      (-> response
          (assoc :session (:session request))
          (assoc-in [:session "__anti-forgery-token"] token)))))

(defn- form-params [request]
  (merge (:form-params request)
         (:multipart-params request)))

(defn- default-request-token [request]
  (or (-> request form-params (get "__anti-forgery-token"))
      (-> request :headers (get "x-csrf-token"))
      (-> request :headers (get "x-xsrf-token"))))

(defn- valid-request? [request read-token]
  (let [user-token   (read-token request)
        stored-token (session-token request)]
    (and user-token
         stored-token
         (crypto/eq? user-token stored-token))))

(defn- get-request? [{method :request-method}]
  (or (= :head method)
      (= :get method)))

(defn- ssl-auth-request? [{cert :ssl-client-cert :as req}]
  (println req)
  cert)

(defn- access-denied [body]
  {:status 403
   :headers {"Content-Type" "text/html"}
   :body body})

(defn wrap-anti-forgery-ssl
  "Middleware that prevents CSRF attacks. Any POST request to the handler
  returned by this function must contain a valid anti-forgery token, or else an
  access-denied response is returned.
  The anti-forgery token can be placed into a HTML page via the
  *anti-forgery-token* var, which is bound to a random key unique to the
  current session. By default, the token is expected to be in a form field
  named '__anti-forgery-token', or in the 'X-CSRF-Token' or 'X-XSRF-Token'
  headers.
  This behavior can be customized by supplying a map of options:
    :read-token
      a function that takes a request and returns an anti-forgery token, or nil
      if the token does not exist.
    :error-response
      the response to return if the anti-forgery token is incorrect or missing."
  [handler & [{:as options}]]
  (fn [request]
    (binding [*anti-forgery-token* (session-token request)]
      (if (and (not (get-request? request))
               (not (ssl-auth-request? request))
               (not (valid-request? request (:read-token options default-request-token))))
        (:error-response options (access-denied "<h1>Invalid anti-forgery token</h1>"))
        (if-let [response (handler request)]
          (assoc-session-token response request *anti-forgery-token*))))))

(defn anti-forgery-field
  "Create a hidden field with the session anti-forgery token as its value.
  This ensures that the form it's inside won't be stopped by the anti-forgery
  middleware."
  []
  (html (hidden-field "__anti-forgery-token" *anti-forgery-token*)))
