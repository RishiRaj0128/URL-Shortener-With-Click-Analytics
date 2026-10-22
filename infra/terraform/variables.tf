variable "aws_region" {
  description = "AWS deployment region"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Target deployment environment (production, staging)"
  type        = string
  default     = "production"
}

variable "app_name" {
  description = "Application name identifier"
  type        = string
  default     = "url-shortener"
}

variable "vpc_cidr" {
  description = "VPC CIDR block"
  type        = string
  default     = "10.0.0.0/16"
}

variable "container_port" {
  description = "Port exposed by the Spring Boot container"
  type        = number
  default     = 8080
}

variable "ecs_desired_count" {
  description = "Desired number of ECS Fargate task replicas"
  type        = number
  default     = 3
}

variable "ecs_cpu" {
  description = "CPU units allocated per Fargate task (1024 = 1 vCPU)"
  type        = number
  default     = 512
}

variable "ecs_memory" {
  description = "Memory allocated per Fargate task in MB"
  type        = number
  default     = 1024
}

variable "container_image" {
  description = "Full Docker image URI (e.g. from Amazon ECR)"
  type        = string
  default     = "123456789012.dkr.ecr.us-east-1.amazonaws.com/url-shortener-backend:latest"
}

variable "db_instance_class" {
  description = "RDS PostgreSQL instance type"
  type        = string
  default     = "db.t4g.small"
}

variable "db_allocated_storage" {
  description = "Initial storage allocated for PostgreSQL database in GB"
  type        = number
  default     = 50
}

variable "redis_node_type" {
  description = "ElastiCache Redis node type"
  type        = string
  default     = "cache.t4g.small"
}

variable "jwt_secret_arn" {
  description = "AWS Secrets Manager ARN containing the 256-bit JWT secret"
  type        = string
  default     = ""
}

variable "ip_salt_arn" {
  description = "AWS Secrets Manager ARN containing the HMAC IP salt"
  type        = string
  default     = ""
}
