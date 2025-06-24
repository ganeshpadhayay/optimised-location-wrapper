package com.ganesh.optimisedlocationapplication.exception

class GpsDisabledException : Exception("GPS provider is disabled")

class PermissionDeniedException : Exception("Location permission is denied")
