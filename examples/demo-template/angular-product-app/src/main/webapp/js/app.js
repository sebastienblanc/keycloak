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

var module = angular.module('product', ['ngKeycloak']);

angular.element(document).ready(function () {
    window.keycloak = new Keycloak('keycloak.json');

    keycloak.init({ onLoad: 'login-required' }).success(function () {
        angular.bootstrap(document, ["product"]);
    }).error(function () {
            window.location.reload();
        });

});

module.controller('GlobalCtrl', function($scope, $http, Auth) {
    $scope.products = [];
    $scope.reloadData = function() {
        $http.get("/database/products").success(function(data) {
            $scope.products = angular.fromJson(data);

        });
    };
    $scope.logout = function(){
      window.location = Auth.createLogoutUrl();
    };
});

module.config(function($httpProvider) {
    $httpProvider.responseInterceptors.push('errorInterceptor');
    $httpProvider.interceptors.push('authInterceptor');
});
