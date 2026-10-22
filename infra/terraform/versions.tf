terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.40"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Production state backend should use encrypted S3 + DynamoDB locking
  # backend "s3" {
  #   bucket         = "url-shortener-terraform-state-prod"
  #   key            = "production/terraform.tfstate"
  #   region         = "us-east-1"
  #   dynamodb_table = "url-shortener-terraform-locks"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "URL-Shortener"
      Environment = var.environment
      ManagedBy   = "Terraform"
    }
  }
}
