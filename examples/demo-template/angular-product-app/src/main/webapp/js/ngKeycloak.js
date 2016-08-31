/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

'use strict';
angular.module('ngKeycloak', [])
  .factory('Auth', function() {
    return  window.keycloak;
  })
  .factory('authInterceptor', function($q, Auth) {
    return {
        request: function (config) {
            var deferred = $q.defer();
            if (Auth.token) {
                Auth.updateToken(5).success(function() {
                    config.headers = config.headers || {};
                    config.headers.Authorization = 'Bearer ' + Auth.token;
                    deferred.resolve(config);
                }).error(function() {
                        deferred.reject('Failed to refresh token');
                    });
            }
            return deferred.promise;
        }
    };
  })
  .factory('errorInterceptor', function($q) {
      return function(promise) {
          return promise.then(function(response) {
              return response;
          }, function(response) {
              if (response.status == 401) {
                  console.log('session timeout?');
                  logout();
              } else if (response.status == 403) {
                  alert("Forbidden");
              } else if (response.status == 404) {
                  alert("Not found");
              } else if (response.status) {
                  if (response.data && response.data.errorMessage) {
                      alert(response.data.errorMessage);
                  } else {
                      alert("An unexpected server error has occurred");
                  }
              }
              return $q.reject(response);
          });
      };
});
