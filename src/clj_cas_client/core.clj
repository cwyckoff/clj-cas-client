(ns clj-cas-client.core
  (:use ring.util.response)
  (:require [clojure.tools.logging :as log]
            [ring.middleware.params :refer [wrap-params]])
  (:import (org.jasig.cas.client.validation Cas10TicketValidator
                                            TicketValidationException)))
(def artifact-parameter-name "ticket")
(def const-cas-principal "_const_cas_principal_")

(defprotocol Validator
  (validate [v ticket service]))

(extend-type Cas10TicketValidator
  Validator
  (validate [v ticket service] (.validate v ticket service)))

(defn validator-maker [cas-server-fn]
  (Cas10TicketValidator. (cas-server-fn)))

(defn- valid? [request]
  (or (get-in request [:session const-cas-principal])
      (get-in request [:session (keyword const-cas-principal)])
      (get-in request [:query-params artifact-parameter-name])
      (get-in request [:query-params (keyword artifact-parameter-name)])))

(defn assertion->principal [assertion]
  (.getName (.getPrincipal assertion)))

(defn authentication-filter
  [handler cas-server-fn service-fn no-redirect?]
  (fn [request]
    (if (valid? request)
      (handler request)
      (if (no-redirect? request)
        {:status 403}
        (redirect (str (cas-server-fn) "/login?service=" (service-fn)))))))

(defn session-principal [res assertion]
  (let [s (assertion->principal assertion)]
    (assoc-in res [:session const-cas-principal] s)))

(defn request-principal [req assertion]
  (let [s (assertion->principal assertion)]
    (update-in req [:query-params] assoc const-cas-principal s)))

(defn ticket [r] (or (get-in r [:query-params artifact-parameter-name])
                     (get-in r [:query-params (keyword artifact-parameter-name)])))

(defn ticket-validation-filter-maker [validator-maker]
  (fn [handler cas-server-fn service-fn]
    (let [ticket-validator (validator-maker cas-server-fn)]
      (fn [request]
        (if-let [t (ticket request)]
          (try
            (let [assertion (validate ticket-validator t (service-fn))]
              (session-principal (handler (request-principal request assertion)) assertion))
            (catch TicketValidationException e
              (log/error "Ticket validation exception " e)
              {:status 403}))
          (handler request))))))

(def ticket-validation-filter (ticket-validation-filter-maker validator-maker))

(defn user-principal-filter [handler]
  (fn [request]
    (if-let [principal (or (get-in request [:query-params const-cas-principal])
                           (get-in request [:query-params (keyword const-cas-principal)])
                           (get-in request [:session const-cas-principal])
                           (get-in request [:session (keyword const-cas-principal)]))]
      (handler (assoc request :username principal))
      (handler request))))

(defn cas
  "Middleware that requires the user to authenticate with a CAS server.

  The users's username is added to the request map under the :username key.

  Accepts the following options:

    :enabled      - when false, the middleware does nothing
    :no-redirect? - if this predicate function returns true for a request, a
                    403 Forbidden response will be returned instead of a 302
                    Found redirect"
  [handler cas-server-fn service-fn & {:as options}]
  (let [options (merge {:enabled true
                        :no-redirect? (constantly false)}
                       options)]
    (if (:enabled options)
      (-> handler
          user-principal-filter
          (authentication-filter cas-server-fn service-fn (:no-redirect? options))
          (ticket-validation-filter cas-server-fn service-fn)
          wrap-params)
      handler)))
