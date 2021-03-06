(ns metabase.api.segment-test
  "Tests for /api/segment endpoints."
  (:require [expectations :refer [expect]]
            [metabase
             [http-client :as http]
             [util :as u]]
            [metabase.middleware.util :as middleware.u]
            [metabase.models
             [database :refer [Database]]
             [permissions :as perms]
             [permissions-group :as group]
             [revision :refer [Revision]]
             [segment :as segment :refer [Segment]]
             [table :refer [Table]]]
            [metabase.test
             [data :refer :all]
             [util :as tu]]
            [metabase.test.data.users :refer :all]
            [toucan
             [db :as db]
             [hydrate :refer [hydrate]]]
            [toucan.util.test :as tt]))

;; ## Helper Fns

(defn- user-details [user]
  (select-keys
   user
   [:email :first_name :last_login :is_qbnewb :is_superuser :id :last_name :date_joined :common_name :locale]))

(defn- segment-response [segment]
  (-> (into {} segment)
      (dissoc :id :table_id)
      (update :creator #(into {} %))
      (update :created_at some?)
      (update :updated_at some?)))


;; ## /api/segment/* AUTHENTICATION Tests
;; We assume that all endpoints for a given context are enforced by the same middleware, so we don't run the same
;; authentication test on every single individual endpoint

(expect (get middleware.u/response-unauthentic :body) (http/client :get 401 "segment"))
(expect (get middleware.u/response-unauthentic :body) (http/client :put 401 "segment/13"))


;; ## POST /api/segment

;; test security.  requires superuser perms
(expect "You don't have permissions to do that."
  ((user->client :rasta) :post 403 "segment" {:name       "abc"
                                              :table_id   123
                                              :definition {}}))

;; test validations
(expect {:errors {:name "value must be a non-blank string."}}
  ((user->client :crowberto) :post 400 "segment" {}))

(expect {:errors {:table_id "value must be an integer greater than zero."}}
  ((user->client :crowberto) :post 400 "segment" {:name "abc"}))

(expect {:errors {:table_id "value must be an integer greater than zero."}}
  ((user->client :crowberto) :post 400 "segment" {:name     "abc"
                                                  :table_id "foobar"}))

(expect {:errors {:definition "value must be a map."}}
  ((user->client :crowberto) :post 400 "segment" {:name     "abc"
                                                  :table_id 123}))

(expect {:errors {:definition "value must be a map."}}
  ((user->client :crowberto) :post 400 "segment" {:name       "abc"
                                                  :table_id   123
                                                  :definition "foobar"}))

(expect
  {:name                    "A Segment"
   :description             "I did it!"
   :show_in_getting_started false
   :caveats                 nil
   :points_of_interest      nil
   :creator_id              (user->id :crowberto)
   :creator                 (user-details (fetch-user :crowberto))
   :created_at              true
   :updated_at              true
   :archived                false
   :definition              {:filter ["=" ["field-id" 10] 20]}}
  (tt/with-temp* [Database [{database-id :id}]
                  Table    [{:keys [id]} {:db_id database-id}]]
    (segment-response ((user->client :crowberto) :post 200 "segment"
                       {:name                    "A Segment"
                        :description             "I did it!"
                        :show_in_getting_started false
                        :caveats                 nil
                        :points_of_interest      nil
                        :table_id                id
                        :definition              {:filter [:= [:field-id 10] 20]}}))))


;; ## PUT /api/segment

;; test security.  requires superuser perms
(expect
  "You don't have permissions to do that."
  (tt/with-temp Segment [segment]
    ((user->client :rasta) :put 403 (str "segment/" (:id segment))
     {:name             "abc"
      :definition       {}
      :revision_message "something different"})))

;; test validations
(expect {:errors {:name "value may be nil, or if non-nil, value must be a non-blank string."}}
  ((user->client :crowberto) :put 400 "segment/1" {:name ""}))

(expect {:errors {:revision_message "value must be a non-blank string."}}
  ((user->client :crowberto) :put 400 "segment/1" {:name "abc"}))

(expect {:errors {:revision_message "value must be a non-blank string."}}
  ((user->client :crowberto) :put 400 "segment/1" {:name             "abc"
                                                   :revision_message ""}))

(expect {:errors {:definition "value may be nil, or if non-nil, value must be a map."}}
  ((user->client :crowberto) :put 400 "segment/1" {:name             "abc"
                                                   :revision_message "123"
                                                   :definition       "foobar"}))

(expect
  {:name                    "Costa Rica"
   :description             nil
   :show_in_getting_started false
   :caveats                 nil
   :points_of_interest      nil
   :creator_id              (user->id :rasta)
   :creator                 (user-details (fetch-user :rasta))
   :created_at              true
   :updated_at              true
   :archived                false
   :definition              {:filter ["!=" ["field-id" 2] "cans"]}}
  (tt/with-temp* [Database [{database-id :id}]
                  Table    [{table-id :id} {:db_id database-id}]
                  Segment  [{:keys [id]}   {:table_id table-id}]]
    (segment-response ((user->client :crowberto) :put 200 (format "segment/%d" id)
                       {:id                      id
                        :name                    "Costa Rica"
                        :description             nil
                        :show_in_getting_started false
                        :caveats                 nil
                        :points_of_interest      nil
                        :table_id                456
                        :revision_message        "I got me some revisions"
                        :definition              {:filter [:!= [:field-id 2] "cans"]}}))))

;; Can we archive a Segment with the PUT endpoint?

(expect
  true
  (tt/with-temp Segment [{:keys [id]}]
    ((user->client :crowberto) :put 200 (str "segment/" id)
     {:archived true, :revision_message "Archive the Segment"})
    (db/select-one-field :archived Segment :id id)))

;; Can we unarchive a Segment with the PUT endpoint?
(expect
  false
  (tt/with-temp Segment [{:keys [id]} {:archived true}]
    ((user->client :crowberto) :put 200 (str "segment/" id)
     {:archived false, :revision_message "Unarchive the Segment"})
    (db/select-one-field :archived Segment :id id)))


;; ## DELETE /api/segment/:id

;; test security.  requires superuser perms
(expect
  "You don't have permissions to do that."
  (tt/with-temp Segment [{:keys [id]}]
    ((user->client :rasta) :delete 403 (str "segment/" id) :revision_message "yeeeehaw!")))


;; test validations
(expect {:errors {:revision_message "value must be a non-blank string."}}
  ((user->client :crowberto) :delete 400 "segment/1" {:name "abc"}))

(expect {:errors {:revision_message "value must be a non-blank string."}}
  ((user->client :crowberto) :delete 400 "segment/1" :revision_message ""))

(expect
  {:name                    "Toucans in the rainforest"
   :description             "Lookin' for a blueberry"
   :show_in_getting_started false
   :caveats                 nil
   :points_of_interest      nil
   :creator_id              (user->id :rasta)
   :creator                 (user-details (fetch-user :rasta))
   :created_at              true
   :updated_at              true
   :archived                true
   :definition              nil}
  (tt/with-temp* [Database [{database-id :id}]
                  Table    [{table-id :id} {:db_id database-id}]
                  Segment  [{:keys [id]} {:table_id table-id}]]
    ((user->client :crowberto) :delete 204 (format "segment/%d" id) :revision_message "carryon")
    ;; should still be able to fetch the archived segment
    (segment-response
     ((user->client :crowberto) :get 200 (format "segment/%d" id)))))


;; ## GET /api/segment/:id

;; test security. Requires read perms for the Table it references
(expect
  "You don't have permissions to do that."
  (tt/with-temp* [Database [db]
                  Table    [table   {:db_id (u/get-id db)}]
                  Segment  [segment {:table_id (u/get-id table)}]]
    (perms/revoke-permissions! (group/all-users) db)
    ((user->client :rasta) :get 403 (str "segment/" (u/get-id segment)))))

(expect
  {:name                    "Toucans in the rainforest"
   :description             "Lookin' for a blueberry"
   :show_in_getting_started false
   :caveats                 nil
   :points_of_interest      nil
   :creator_id              (user->id :crowberto)
   :creator                 (user-details (fetch-user :crowberto))
   :created_at              true
   :updated_at              true
   :archived                false
   :definition              {:filter ["=" ["field-id" 2] "cans"]}}
  (tt/with-temp* [Database [{database-id :id}]
                  Table    [{table-id :id} {:db_id database-id}]
                  Segment  [{:keys [id]}   {:creator_id (user->id :crowberto)
                                            :table_id   table-id
                                            :definition {:filter [:= [:field-id 2] "cans"]}}]]
    (segment-response ((user->client :rasta) :get 200 (format "segment/%d" id)))))


;; ## GET /api/segment/:id/revisions

;; test security. Requires read perms for the Table it references
(expect
  "You don't have permissions to do that."
  (tt/with-temp* [Database [db]
                  Table    [table   {:db_id (u/get-id db)}]
                  Segment  [segment {:table_id (u/get-id table)}]]
    (perms/revoke-permissions! (group/all-users) db)
    ((user->client :rasta) :get 403 (format "segment/%d/revisions" (u/get-id segment)))))


(expect
  [{:is_reversion false
    :is_creation  false
    :message      "updated"
    :user         (-> (user-details (fetch-user :crowberto))
                      (dissoc :email :date_joined :last_login :is_superuser :is_qbnewb))
    :diff         {:name {:before "b" :after "c"}}
    :description  "renamed this Segment from \"b\" to \"c\"."}
   {:is_reversion false
    :is_creation  true
    :message      nil
    :user         (-> (user-details (fetch-user :rasta))
                      (dissoc :email :date_joined :last_login :is_superuser :is_qbnewb))
    :diff         {:name       {:after "b"}
                   :definition {:after {:filter [">" ["field-id" 1] 25]}}}
    :description  nil}]
  (tt/with-temp* [Database [{database-id :id}]
                  Table    [{table-id :id} {:db_id database-id}]
                  Segment  [{:keys [id]} {:creator_id (user->id :crowberto)
                                          :table_id   table-id
                                          :definition {:database 123
                                                       :query    {:filter [:= [:field-id 2] "cans"]}}}]
                  Revision [_ {:model       "Segment"
                               :model_id    id
                               :object      {:name "b"
                                             :definition {:filter [:and [:> 1 25]]}}
                               :is_creation true}]
                  Revision [_ {:model    "Segment"
                               :model_id id
                               :user_id  (user->id :crowberto)
                               :object   {:name "c"
                                          :definition {:filter [:and [:> 1 25]]}}
                               :message  "updated"}]]
    (vec
     (for [revision ((user->client :rasta) :get 200 (format "segment/%d/revisions" id))]
       (dissoc revision :timestamp :id)))))


;; ## POST /api/segment/:id/revert

;; test security.  requires superuser perms
(expect
  "You don't have permissions to do that."
  (tt/with-temp Segment [{:keys [id]}]
    ((user->client :rasta) :post 403 (format "segment/%d/revert" id) {:revision_id 56})))


(expect {:errors {:revision_id "value must be an integer greater than zero."}}
  ((user->client :crowberto) :post 400 "segment/1/revert" {}))

(expect {:errors {:revision_id "value must be an integer greater than zero."}}
  ((user->client :crowberto) :post 400 "segment/1/revert" {:revision_id "foobar"}))


(expect
  [ ;; the api response
   {:is_reversion true
    :is_creation  false
    :message      nil
    :user         (-> (user-details (fetch-user :crowberto))
                      (dissoc :email :date_joined :last_login :is_superuser :is_qbnewb))
    :diff         {:name {:before "Changed Segment Name"
                          :after  "One Segment to rule them all, one segment to define them"}}
    :description  "renamed this Segment from \"Changed Segment Name\" to \"One Segment to rule them all, one segment to define them\"."}
   ;; full list of final revisions, first one should be same as the revision returned by the endpoint
   [{:is_reversion true
     :is_creation  false
     :message      nil
     :user         (-> (user-details (fetch-user :crowberto))
                       (dissoc :email :date_joined :last_login :is_superuser :is_qbnewb))
     :diff         {:name {:before "Changed Segment Name"
                           :after  "One Segment to rule them all, one segment to define them"}}
     :description  "renamed this Segment from \"Changed Segment Name\" to \"One Segment to rule them all, one segment to define them\"."}
    {:is_reversion false
     :is_creation  false
     :message      "updated"
     :user         (-> (user-details (fetch-user :crowberto))
                       (dissoc :email :date_joined :last_login :is_superuser :is_qbnewb))
     :diff         {:name {:after  "Changed Segment Name"
                           :before "One Segment to rule them all, one segment to define them"}}
     :description  "renamed this Segment from \"One Segment to rule them all, one segment to define them\" to \"Changed Segment Name\"."}
    {:is_reversion false
     :is_creation  true
     :message      nil
     :user         (-> (user-details (fetch-user :rasta))
                       (dissoc :email :date_joined :last_login :is_superuser :is_qbnewb))
     :diff         {:name        {:after "One Segment to rule them all, one segment to define them"}
                    :description {:after "One segment to bring them all, and in the DataModel bind them"}
                    :definition  {:after {:filter ["=" ["field-id" 2] "cans"]}}}
     :description  nil}]]
  (tt/with-temp* [Database [{database-id :id}]
                  Table    [{table-id :id}    {:db_id database-id}]
                  Segment  [{:keys [id]}      {:creator_id              (user->id :crowberto)
                                               :table_id                table-id
                                               :name                    "One Segment to rule them all, one segment to define them"
                                               :description             "One segment to bring them all, and in the DataModel bind them"
                                               :show_in_getting_started false
                                               :caveats                 nil
                                               :points_of_interest      nil
                                               :definition              {:filter [:= [:field-id 2] "cans"]}}]
                  Revision [{revision-id :id} {:model       "Segment"
                                               :model_id    id
                                               :object      {:creator_id              (user->id :crowberto)
                                                             :table_id                table-id
                                                             :name                    "One Segment to rule them all, one segment to define them"
                                                             :description             "One segment to bring them all, and in the DataModel bind them"
                                                             :show_in_getting_started false
                                                             :caveats                 nil
                                                             :points_of_interest      nil
                                                             :definition              {:filter [:= [:field-id 2] "cans"]}}
                                               :is_creation true}]
                  Revision [_                 {:model    "Segment"
                                               :model_id id
                                               :user_id  (user->id :crowberto)
                                               :object   {:creator_id              (user->id :crowberto)
                                                          :table_id                table-id
                                                          :name                    "Changed Segment Name"
                                                          :description             "One segment to bring them all, and in the DataModel bind them"
                                                          :show_in_getting_started false
                                                          :caveats                 nil
                                                          :points_of_interest      nil
                                                          :definition              {:filter [:= [:field-id 2] "cans"]}}
                                               :message  "updated"}]]
    [(dissoc ((user->client :crowberto) :post 200 (format "segment/%d/revert" id) {:revision_id revision-id}) :id :timestamp)
     (doall (for [revision ((user->client :crowberto) :get 200 (format "segment/%d/revisions" id))]
              (dissoc revision :timestamp :id)))]))


;;; GET /api/segement/
(tt/expect-with-temp [Segment [segment-1 {:name "Segment 1"}]
                      Segment [segment-2 {:name "Segment 2"}]
                      Segment [_         {:archived true}]] ; inactive segments shouldn't show up
  (tu/mappify (hydrate [segment-1
                        segment-2] :creator))
  ((user->client :rasta) :get 200 "segment/"))


;;; PUT /api/segment/id. Can I update a segment's name without specifying `:points_of_interest` and `:show_in_getting_started`?
(expect
  (tt/with-temp Segment [segment]
    ;; just make sure API call doesn't barf
    ((user->client :crowberto) :put 200 (str "segment/" (u/get-id segment))
     {:name             "Cool name"
      :revision_message "WOW HOW COOL"
      :definition       {}})
    true))

;; Test related/recommended entities
(expect
  #{:table :metrics :segments :linked-from}
  (tt/with-temp* [Segment [{segment-id :id}]]
    (-> ((user->client :crowberto) :get 200 (format "segment/%s/related" segment-id)) keys set)))
