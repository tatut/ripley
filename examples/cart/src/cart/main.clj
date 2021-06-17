(ns cart.main
  "Shopping cart PostgreSQL logical decoding example"
  (:require [ripley.html :as h]
            [compojure.core :refer [routes GET]]
            [compojure.route :as route]
            [org.httpkit.server :as server]
            [ripley.live.context :as context]
            [ripley.live.collection :refer [live-collection]]
            [ripley.integration.postgresql :as pg]
            [ripley.live.source :as source])
  (:import (java.sql DriverManager Connection PreparedStatement ResultSet)))

(defn- with-ps [c sql args func]
  (with-open [ps (.prepareStatement c sql)]
    (dorun
     (map-indexed (fn [i val]
                    (.setObject ps (inc i) val))
                  args))
    (func ps)))

(defn u! [c sql & args]
  (with-ps c sql args
    #(.executeUpdate %)))

(defn q [c sql & args]
  ;; very simple jdbc "wrapper", use some better sql library in real use!
  (with-ps c sql args
    (fn [ps]
      (with-open [rs (.executeQuery ps)]
        (let [md (.getMetaData rs)
              names (for [i (range (.getColumnCount md))]
                      (.getColumnName md (inc i)))]
          (loop [rows []]
            (if-not (.next rs)
              rows
              (recur (conj rows
                           (zipmap names
                                   (map #(.getObject rs %) names)))))))))))



(defn db []
  ;; use a connection pool in real apps!
  (DriverManager/getConnection (str "jdbc:postgresql://localhost:5432/"
                                    (System/getenv "USER"))))

(defn set-quantity! [{:strs [order_id product_id]} new-quantity]
  (when-not (neg? new-quantity)
    (u! (db) "UPDATE orderproducts SET quantity=? WHERE order_id=? AND product_id=?" new-quantity order_id product_id)))

(defn order-row [{:strs [name quantity price total] :as row}]
  (h/html
   [:div {:style "border-bottom: solid 1px black; margin-bottom: 5px;"}
    [:div [:b "product: "] name]
    [:div [:b "quantity: "] quantity
     [:button {:on-click #(set-quantity! row (inc quantity))} "+"]
     [:button {:on-click #(set-quantity! row (dec quantity))} "-"]]
    [:div [:b "price: "] price]
    [:div [:b "total: "] total]]))

(defn order-page [cart-source]
  (def *cs cart-source)
  (h/html
     [:html
      [:head
       (h/live-client-script "/__ripley-live")]
      [:body
       "hello cart"

       (live-collection {:key (fn [{:strs [product_id order_id]}]
                                [product_id order_id])
                         :render order-row
                         :source cart-source})

       [::h/live (source/c= (reduce + (map (fn [{:strs [quantity price]}]
                                             (* quantity price))
                                           %cart-source)))
        (fn [total]
          (h/html
           [:div
            [:b "TOTAL: " total]]))]]]))

(defn cart-source
  "Create an autoupdating source for cart contents query."
  [c id]
  (pg/collection-source
   {:initial-state
    (q c
       (str "SELECT p.id as product_id, o.id as order_id,"
            " p.name, p.price, op.quantity, "
            "p.price*op.quantity AS total"
            " FROM orderproducts op"
            " JOIN orders o ON op.order_id=o.id"
            " JOIN products p ON op.product_id = p.id"
            " WHERE o.id=?")
       id)
    :changes [{:table "public.orderproducts"
               :type #{:UPDATE}
               :update (fn [order-products {{:strs [product_id order_id quantity]} :values}]
                         (mapv (fn [{pid "product_id" oid "order_id" price "price"
                                     :as op}]
                                 (if (and (= pid product_id) (= oid order_id))
                                   (merge op
                                          {"quantity" quantity
                                           "total" (* quantity price)})
                                   op))
                               order-products))}]}))

(def cart-routes
  (routes
   (GET "/order/:id" [id]
        (h/render-response
         #(order-page
           (cart-source (db) (Long/parseLong id)))))
   (route/resources "/")
   (context/connection-handler "/__ripley-live")))

(defonce server (atom nil))

(defn restart []
  (swap! server
         (fn [old-server]
           (when old-server (old-server))
           (server/run-server cart-routes {:port 3000}))))

(defn -main [& _]
  (pg/start! {:replication-slot-name "ripley1"
              :connection (pg/replication-connection {:database (System/getenv "USER")})})
  (restart))
