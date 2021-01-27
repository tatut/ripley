(ns ripley.integration.manifold-test
  (:require [ripley.integration.manifold :as sut]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [clojure.test :as t :refer [testing is deftest]]
            [ripley.live.protocols :as p]
            [clojure.string :as str]))


(deftest deferred
  (let [d (d/deferred)
        s (sut/deferred-source d)]
    (is (nil? (p/current-value s)))
    (p/listen! s println)
    (is (= "42\n"
           (with-out-str (d/success! d 42))))))

(deftest deferred-error-not-sent
  (let [d (d/deferred)
        s (sut/deferred-source d)]
    (p/listen! s println)
    (is (str/blank?
         (with-out-str
           (d/error! d (ex-info "No way" {:no :way})))))))

(deftest deferred-error-sent
  (let [d (d/deferred)
        s (sut/deferred-source {:error-value-fn ex-message} d)]
    (p/listen! s println)
    (is (= "No way\n"
           (with-out-str
             (d/error! d (ex-info "No way" {:no :way})))))))

(deftest stream
  (let [stream (s/stream 1)
        s (sut/stream-source stream)]
    (is (nil? (p/current-value s)))
    (p/listen! s println)
    (is (= "hello\n"
           (with-out-str
             (s/put! stream "hello"))))))
